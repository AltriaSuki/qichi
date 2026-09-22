package app.qichi.server.ai

import app.qichi.server.config.AiConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 一轮对话里的一句。 */
data class AiMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { User("user"), Assistant("assistant") }
}

data class AiRequest(
    val system: String,
    val messages: List<AiMessage>,
    val maxTokens: Int = 1024,
)

data class AiResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val model: String,
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

/** 大多数模型服务兼容的 /chat/completions 格式（OpenAI、DeepSeek、通义、Kimi、智谱……）。 */
class OpenAiCompatibleProvider(
    baseUrl: String,
    private val apiKey: String,
    override val model: String,
    private val http: HttpClient,
) : AiGateway {
    private val endpoint = baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }

    override suspend fun complete(request: AiRequest): AiResult {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", request.system)
                }
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    }
                }
            }
        }
        val response = try {
            http.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: Exception) {
            throw AiProviderException("连接模型服务失败：${e.javaClass.simpleName}", retryable = true)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw failure("openai-compatible", response.status, text)
        val root = parse(text)
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiProviderException("响应里没有回答", retryable = false)
        val usage = root["usage"]?.jsonObject
        return AiResult(
            text = content.trim(),
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model,
        )
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

    override suspend fun complete(request: AiRequest): AiResult {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("system", request.system)
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    }
                }
            }
        }
        val response = try {
            http.post(endpoint) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: Exception) {
            throw AiProviderException("连接模型服务失败：${e.javaClass.simpleName}", retryable = true)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw failure("anthropic", response.status, text)
        val root = parse(text)
        val content = root["content"]?.jsonArray
            ?.mapNotNull { block -> block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException("响应里没有回答", retryable = false)
        val usage = root["usage"]?.jsonObject
        return AiResult(
            text = content.trim(),
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0,
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model,
        )
    }
}

private fun parse(text: String): JsonObject = try {
    json.parseToJsonElement(text).jsonObject
} catch (e: Exception) {
    throw AiProviderException("响应不是合法的 JSON", retryable = true)
}
