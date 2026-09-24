package app.qichi.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiGatewayTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val request = AiRequest("你是助手", listOf(AiMessage(AiMessage.Role.User, "周六去哪片海？")), maxTokens = 300)

    private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `openai 兼容：地址、密钥、系统提示放在 messages 最前；解析回答与用量`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { req ->
            seen = req
            respond(
                """{"model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":" 去北边那片。 "}}],"usage":{"prompt_tokens":120,"completion_tokens":30}}""",
                HttpStatusCode.OK, json,
            )
        }
        val provider = OpenAiCompatibleProvider("https://api.example.com/v1/", "sk-test", "deepseek-chat", HttpClient(engine))
        val result = provider.complete(request)
        assertEquals("去北边那片。", result.text)
        assertEquals(120, result.inputTokens)
        assertEquals(30, result.outputTokens)
        assertEquals("deepseek-chat", result.model)

        val req = seen!!
        assertEquals("https://api.example.com/v1/chat/completions", req.url.toString())
        assertEquals("Bearer sk-test", req.headers[HttpHeaders.Authorization])
        val body = req.bodyJson()
        assertEquals(300, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("你是助手", messages[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic：地址、版本头、system 单独给；解析文本块与用量`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { req ->
            seen = req
            respond(
                """{"model":"claude-sonnet-5","content":[{"type":"text","text":"去北边"},{"type":"text","text":"那片。"}],"usage":{"input_tokens":88,"output_tokens":12}}""",
                HttpStatusCode.OK, json,
            )
        }
        val provider = AnthropicProvider("https://api.anthropic.com/v1", "key-test", "claude-sonnet-5", HttpClient(engine))
        val result = provider.complete(request)
        assertEquals("去北边那片。", result.text)
        assertEquals(88, result.inputTokens)
        assertEquals(12, result.outputTokens)

        val req = seen!!
        assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
        assertEquals("key-test", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        val body = req.bodyJson()
        assertEquals("你是助手", body["system"]!!.jsonPrimitive.content)
        assertEquals(listOf("user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `限流和 5xx 可以重试，密钥错误不行`() = runBlocking {
        fun provider(status: HttpStatusCode) =
            OpenAiCompatibleProvider("https://api.example.com", "k", "m", HttpClient(MockEngine { respond("""{"error":"x"}""", status, json) }))
        assertTrue(assertFailsWith<AiProviderException> { provider(HttpStatusCode.TooManyRequests).complete(request) }.retryable)
        assertTrue(assertFailsWith<AiProviderException> { provider(HttpStatusCode.BadGateway).complete(request) }.retryable)
        val unauthorized = assertFailsWith<AiProviderException> { provider(HttpStatusCode.Unauthorized).complete(request) }
        assertFalse(unauthorized.retryable)
        assertFalse(unauthorized.message!!.contains("\"k\""), "日志里不带密钥")
    }

    @Test
    fun `提示词模板：变量替换，分隔线前是系统提示；缺变量报错`() {
        val prompts = Prompts { "你是助手。\n---\n{{asker}} 问：{{prompt}}" }
        val rendered = prompts.render("x", mapOf("asker" to "阿栖", "prompt" to "去哪？"))
        assertEquals("你是助手。", rendered.system)
        assertEquals("阿栖 问：去哪？", rendered.user)
        assertFailsWith<IllegalArgumentException> { prompts.render("x", mapOf("asker" to "阿栖")) }
        // 真实的模板文件能加载
        assertTrue(Prompts().render("chat_answer", mapOf("now" to "", "sources" to "", "focus" to "", "asker" to "阿栖", "history" to "", "prompt" to "?")).system.isNotBlank())
    }
}
