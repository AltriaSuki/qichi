package app.qichi.server.ai

import app.qichi.server.config.AiConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 一轮对话里的一句。模型要调工具时是 Assistant 且 [toolCalls] 非空；
 * 工具的结果是 Tool，[toolCallId] 对上那次调用。
 */
data class AiMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    enum class Role(val wire: String) { User("user"), Assistant("assistant"), Tool("tool") }
}

/** 给模型用的一个工具（P11）：名字、说明、参数的 JSON Schema。 */
data class AiTool(val name: String, val description: String, val parameters: JsonObject)

/** 模型要调用的一个工具。[arguments] 是模型给的 JSON 文本，可能不合法，由执行的一方检查。 */
data class AiToolCall(val id: String, val name: String, val arguments: String)

data class AiRequest(
    val system: String,
    val messages: List<AiMessage>,
    val maxTokens: Int = 1024,
    /** 整个请求最多等多久（按功能分开：问 AI 长一些） */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    /** 模型可以调用的工具；为空就是普通的一问一答 */
    val tools: List<AiTool> = emptyList(),
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /**
         * 流式回复时，两段之间最多隔多久没动静就算断了。
         * 会「先想再答」的模型开头可能很久一个字都不发（2026-09-26 实测 gpt-6-sol 忙时要 50–100 秒），不能设太短。
         */
        const val STREAM_IDLE_MS = 100_000L
    }
}

/** 模型这一轮的回复。[toolCalls] 非空表示它要先查资料，[text] 可能为空。 */
data class AiResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val model: String,
    val toolCalls: List<AiToolCall> = emptyList(),
)

/**
 * 大模型调用失败。[retryable] 为 true 表示可以稍后再试（限流、服务暂时不可用、网络问题）。
 * message 只给日志看，不含密钥。
 */
class AiProviderException(message: String, val retryable: Boolean) : Exception(message)

/**
 * AI 网关（docs/02-architecture.md「AI 网关」）：一个接口，两种实现，按 AI_PROVIDER 选择。
 * 所有调用都在后台任务里进行，接口层只负责入队。
 */
interface AiGateway {
    val model: String

    suspend fun complete(request: AiRequest): AiResult

    /**
     * 边生成边回调 [onText]（参数是到目前为止的全文），最后返回完整结果。
     * 不支持流式的实现（或服务商没按流式回）就整段生成完回调一次。
     */
    suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult =
        complete(request).also { onText(it.text) }

    companion object {
        const val OPENAI_COMPATIBLE = "openai-compatible"
        const val ANTHROPIC = "anthropic"

        /** 没配置 AI 时返回 null（AI 接口返回 ai_unavailable）。 */
        fun fromConfig(config: AiConfig, engine: HttpClientEngine = CIO.create()): AiGateway? {
            if (!config.isConfigured) return null
            val http = HttpClient(engine) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    requestTimeoutMillis = 120_000
                }
            }
            return when (config.provider) {
                OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(config.baseUrl!!, config.apiKey!!, config.model!!, http)
                ANTHROPIC -> AnthropicProvider(config.baseUrl!!, config.apiKey!!, config.model!!, http)
                else -> null
            }
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** 状态码到「能不能重试」：限流与服务端错误可以，其它 4xx（密钥错、参数错）不行。 */
private fun failure(provider: String, status: HttpStatusCode, body: String): AiProviderException {
    val retryable = status == HttpStatusCode.TooManyRequests || status.value >= 500
    return AiProviderException("$provider 返回 ${status.value}：${body.take(300)}", retryable)
}

/** 请求级的等待上限；测试里的客户端没装 HttpTimeout 时不设。 */
private fun HttpRequestBuilder.limits(http: HttpClient, request: AiRequest, streaming: Boolean) {
    if (http.pluginOrNull(HttpTimeout) == null) return
    timeout {
        requestTimeoutMillis = request.timeoutMillis
        if (streaming) socketTimeoutMillis = AiRequest.STREAM_IDLE_MS
    }
}

/** 读一个 SSE（text/event-stream）回复，每个 data 行交给 [onData]；遇到 [DONE] 结束。 */
private suspend fun readEvents(response: HttpResponse, onData: suspend (JsonObject) -> Unit) {
    val channel = response.bodyAsChannel()
    while (true) {
        val line = channel.readUTF8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
        onData(obj)
    }
}

/** 流式回复里一段段到达的工具调用，按序号拼起来。 */
private class PartialCall {
    var id = ""
    var name = ""
    val arguments = StringBuilder()
}

private fun Map<Int, PartialCall>.toCalls(): List<AiToolCall> =
    entries.sortedBy { it.key }.filter { it.value.name.isNotEmpty() }.map { (i, p) ->
        AiToolCall(p.id.ifEmpty { "call_$i" }, p.name, p.arguments.toString().ifBlank { "{}" })
    }

private fun HttpResponse.isEventStream() = contentType()?.match(ContentType.Text.EventStream) == true

/** 连接出错统一换成可重试的 [AiProviderException]；自己抛的原样往外抛。 */
private suspend fun <T> connecting(block: suspend () -> T): T = try {
    block()
} catch (e: AiProviderException) {
    throw e
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    throw AiProviderException("连接模型服务失败：${e.javaClass.simpleName}", retryable = true)
}

/** 大多数模型服务兼容的 /chat/completions 格式（OpenAI、DeepSeek、通义、Kimi、智谱……）。 */
class OpenAiCompatibleProvider(
    baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val http: HttpClient,
) : AiGateway {
    private val endpoint = baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }

    private fun body(request: AiRequest, stream: Boolean) = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        if (stream) {
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", request.system)
            }
            request.messages.forEach { m -> add(message(m)) }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    }
                }
            }
        }
    }

    private fun message(m: AiMessage) = buildJsonObject {
        put("role", m.role.wire)
        when {
            m.role == AiMessage.Role.Tool -> {
                put("tool_call_id", m.toolCallId)
                put("content", m.content)
            }
            m.toolCalls.isNotEmpty() -> {
                if (m.content.isEmpty()) put("content", JsonNull) else put("content", m.content)
                putJsonArray("tool_calls") {
                    m.toolCalls.forEach { c ->
                        addJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", c.name)
                                put("arguments", c.arguments)
                            }
                        }
                    }
                }
            }
            else -> put("content", m.content)
        }
    }

    private fun toolCall(el: JsonElement, index: Int): AiToolCall? {
        val o = el as? JsonObject ?: return null
        val f = o["function"] as? JsonObject ?: return null
        val name = f["name"]?.jsonPrimitive?.contentOrNull ?: return null
        // 个别服务商把参数直接给成对象
        val args = when (val a = f["arguments"]) {
            is JsonPrimitive -> a.contentOrNull
            null -> null
            else -> a.toString()
        }
        return AiToolCall(o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index", name, args?.ifBlank { null } ?: "{}")
    }

    private fun HttpRequestBuilder.setup(request: AiRequest, stream: Boolean) {
        header(HttpHeaders.Authorization, "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(body(request, stream).toString())
        limits(http, request, stream)
    }

    private fun result(text: String): AiResult {
        val root = parse(text)
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull
        val calls = (message?.get("tool_calls") as? JsonArray).orEmpty().mapIndexedNotNull { i, el -> toolCall(el, i) }
        if (content == null && calls.isEmpty()) throw AiProviderException("响应里没有回答", retryable = false)
        val usage = root["usage"]?.jsonObject
        return AiResult(
            text = content.orEmpty().trim(),
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model,
            toolCalls = calls,
        )
    }

    override suspend fun complete(request: AiRequest): AiResult {
        val response = connecting { http.post(endpoint) { setup(request, stream = false) } }
        val text = connecting { response.bodyAsText() }
        if (!response.status.isSuccess()) throw failure("openai-compatible", response.status, text)
        return result(text)
    }

    override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult = connecting {
        http.preparePost(endpoint) { setup(request, stream = true) }.execute { response ->
            if (!response.status.isSuccess()) throw failure("openai-compatible", response.status, response.bodyAsText())
            // 有的服务商不支持流式，照常整段回
            if (!response.isEventStream()) return@execute result(response.bodyAsText()).also { onText(it.text) }
            val sb = StringBuilder()
            val calls = sortedMapOf<Int, PartialCall>()
            var tokensIn = 0
            var tokensOut = 0
            var usedModel = model
            readEvents(response) { obj ->
                obj["model"]?.jsonPrimitive?.contentOrNull?.let { usedModel = it }
                (obj["usage"] as? JsonObject)?.let { u ->
                    tokensIn = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: tokensIn
                    tokensOut = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: tokensOut
                }
                val delta = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                val piece = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    sb.append(piece)
                    onText(sb.toString())
                }
                // 工具调用分段到达：第一段带 id 和名字，后面只有参数的片段
                (delta?.get("tool_calls") as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val p = calls.getOrPut(o["index"]?.jsonPrimitive?.intOrNull ?: 0) { PartialCall() }
                    o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { p.id = it }
                    val f = o["function"] as? JsonObject
                    f?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && p.name.isEmpty() }?.let { p.name = it }
                    f?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { p.arguments.append(it) }
                }
            }
            val toolCalls = calls.toCalls()
            if (sb.isBlank() && toolCalls.isEmpty()) throw AiProviderException("响应里没有回答", retryable = true)
            AiResult(sb.toString().trim(), tokensIn, tokensOut, usedModel, toolCalls)
        }
    }
}

/** Anthropic 的 /v1/messages。 */
class AnthropicProvider(
    baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val http: HttpClient,
) : AiGateway {
    private val endpoint = baseUrl.trimEnd('/').removeSuffix("/v1").removeSuffix("/v1/messages") + "/v1/messages"

    private fun HttpRequestBuilder.setup(request: AiRequest, stream: Boolean) {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("model", model)
                put("max_tokens", request.maxTokens)
                put("system", request.system)
                if (stream) put("stream", true)
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") {
                        request.tools.forEach { t ->
                            addJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("input_schema", t.parameters)
                            }
                        }
                    }
                }
                putJsonArray("messages") { messages(request.messages).forEach { add(it) } }
            }.toString(),
        )
        limits(http, request, stream)
    }

    /**
     * Anthropic 的对话格式：工具调用是助手消息里的 tool_use 块；
     * 工具结果是用户消息里的 tool_result 块，同一轮的几个结果要放进同一条用户消息。
     */
    private fun messages(list: List<AiMessage>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < list.size) {
            val m = list[i]
            when {
                m.role == AiMessage.Role.Tool -> {
                    val results = list.drop(i).takeWhile { it.role == AiMessage.Role.Tool }
                    out += buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            results.forEach { r ->
                                addJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", r.toolCallId)
                                    put("content", r.content)
                                }
                            }
                        }
                    }
                    i += results.size
                    continue
                }
                m.toolCalls.isNotEmpty() -> out += buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (m.content.isNotBlank()) addJsonObject {
                            put("type", "text")
                            put("text", m.content)
                        }
                        m.toolCalls.forEach { c ->
                            addJsonObject {
                                put("type", "tool_use")
                                put("id", c.id)
                                put("name", c.name)
                                put("input", runCatching { json.parseToJsonElement(c.arguments) as JsonObject }.getOrElse { buildJsonObject { } })
                            }
                        }
                    }
                }
                else -> out += buildJsonObject {
                    put("role", m.role.wire)
                    put("content", m.content)
                }
            }
            i++
        }
        return out
    }

    private fun result(text: String): AiResult {
        val root = parse(text)
        val blocks = root["content"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        fun type(b: JsonObject) = b["type"]?.jsonPrimitive?.contentOrNull
        val content = blocks.filter { type(it) == "text" }.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
        val calls = blocks.filter { type(it) == "tool_use" }.mapIndexedNotNull { i, b ->
            val name = b["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            AiToolCall(b["id"]?.jsonPrimitive?.contentOrNull ?: "call_$i", name, (b["input"] as? JsonObject)?.toString() ?: "{}")
        }
        if (content.isBlank() && calls.isEmpty()) throw AiProviderException("响应里没有回答", retryable = false)
        val usage = root["usage"]?.jsonObject
        return AiResult(
            text = content.trim(),
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model,
            toolCalls = calls,
        )
    }

    override suspend fun complete(request: AiRequest): AiResult {
        val response = connecting { http.post(endpoint) { setup(request, stream = false) } }
        val text = connecting { response.bodyAsText() }
        if (!response.status.isSuccess()) throw failure("anthropic", response.status, text)
        return result(text)
    }

    override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult = connecting {
        http.preparePost(endpoint) { setup(request, stream = true) }.execute { response ->
            if (!response.status.isSuccess()) throw failure("anthropic", response.status, response.bodyAsText())
            if (!response.isEventStream()) return@execute result(response.bodyAsText()).also { onText(it.text) }
            val sb = StringBuilder()
            val calls = sortedMapOf<Int, PartialCall>()
            var tokensIn = 0
            var tokensOut = 0
            var usedModel = model
            readEvents(response) { obj ->
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_start" -> (obj["content_block"] as? JsonObject)
                        ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                        ?.let { b ->
                            val p = calls.getOrPut(index) { PartialCall() }
                            p.id = b["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            p.name = b["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        }
                    "message_start" -> obj["message"]?.jsonObject?.let { m ->
                        m["model"]?.jsonPrimitive?.contentOrNull?.let { usedModel = it }
                        tokensIn = m["usage"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: tokensIn
                    }
                    "content_block_delta" -> {
                        val delta = obj["delta"] as? JsonObject
                        when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                                sb.append(it)
                                onText(sb.toString())
                            }
                            // 工具参数的 JSON 分段到达
                            "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let { calls[index]?.arguments?.append(it) }
                        }
                    }
                    "message_delta" -> tokensOut = obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: tokensOut
                    "error" -> throw AiProviderException("anthropic 流式出错：${obj["error"]?.toString()?.take(200)}", retryable = true)
                }
            }
            val toolCalls = calls.toCalls()
            if (sb.isBlank() && toolCalls.isEmpty()) throw AiProviderException("响应里没有回答", retryable = true)
            AiResult(sb.toString().trim(), tokensIn, tokensOut, usedModel, toolCalls)
        }
    }
}

private fun parse(text: String): JsonObject = try {
    json.parseToJsonElement(text).jsonObject
} catch (e: Exception) {
    throw AiProviderException("响应不是合法的 JSON", retryable = true)
}
