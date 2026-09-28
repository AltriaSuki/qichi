package app.qichi.server.ai

import app.qichi.server.ai.ResilientGateway.Companion.withUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AI 通路优化：当场重试、长度用光时放宽、截断的识别、用量估算。 */
class ResilientGatewayTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val sse = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    private val request = AiRequest("你是助手", listOf(AiMessage(AiMessage.Role.User, "周六去哪？")), maxTokens = 300)
    private fun HttpRequestData.maxTokens() = Json.parseToJsonElement((body as TextContent).text).jsonObject["max_tokens"]!!.jsonPrimitive.int

    private fun openai(engine: MockEngine) = ResilientGateway(OpenAiCompatibleProvider("https://api.example.com", "k", "m", HttpClient(engine)), pauses = listOf(0L, 0L))

    private val ok = """{"model":"m","choices":[{"message":{"content":"去北边。"},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":5}}"""

    @Test
    fun `很快失败的限流、5xx、连不上当场重试，成功就不交回队列`() = runBlocking {
        var calls = 0
        val gateway = openai(MockEngine {
            calls++
            when (calls) {
                1 -> respond("""{"error":"busy"}""", HttpStatusCode.TooManyRequests, json)
                2 -> respond("bad gateway", HttpStatusCode.BadGateway)
                else -> respond(ok, HttpStatusCode.OK, json)
            }
        })
        assertEquals("去北边。", gateway.complete(request).text)
        assertEquals(3, calls)
    }

    @Test
    fun `次数用完、不能重试的错误、慢慢失败的都原样交出去`() = runBlocking {
        var calls = 0
        val always503 = openai(MockEngine { calls++; respond("x", HttpStatusCode.ServiceUnavailable) })
        assertTrue(assertFailsWith<AiProviderException> { always503.complete(request) }.retryable)
        assertEquals(3, calls, "第一次加两次重试")

        calls = 0
        val badKey = openai(MockEngine { calls++; respond("x", HttpStatusCode.Unauthorized) })
        assertFalse(assertFailsWith<AiProviderException> { badKey.complete(request) }.retryable)
        assertEquals(1, calls)

        calls = 0
        val slow = ResilientGateway(OpenAiCompatibleProvider("https://api.example.com", "k", "m", HttpClient(MockEngine { calls++; respond("x", HttpStatusCode.GatewayTimeout) })),
            pauses = listOf(0L), fastFailMillis = 0L)
        assertFailsWith<AiProviderException> { slow.complete(request) }
        assertEquals(1, calls, "等了很久才失败的（多半超时）交给队列")
    }

    @Test
    fun `流式已经出了字再断：不当场重试，免得回答重来一遍`() = runBlocking {
        var calls = 0
        val flaky = ResilientGateway(object : AiGateway {
            override val model = "m"
            override suspend fun complete(request: AiRequest) = error("不用")
            override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult {
                calls++
                onText("去北")
                throw AiProviderException("断了", retryable = true)
            }
        }, pauses = listOf(0L, 0L))
        val seen = mutableListOf<String>()
        assertFailsWith<AiProviderException> { flaky.stream(request) { seen += it } }
        assertEquals(1, calls)
        assertEquals(listOf("去北"), seen)
    }

    @Test
    fun `想完就用光了长度、一个字没写：上限翻倍再问一次，用量两次加起来`() = runBlocking {
        val seen = mutableListOf<Int>()
        val gateway = openai(MockEngine { req ->
            seen += req.maxTokens()
            if (seen.size == 1) {
                respond("""{"model":"m","choices":[{"message":{"content":null},"finish_reason":"length"}],"usage":{"prompt_tokens":50,"completion_tokens":300}}""", HttpStatusCode.OK, json)
            } else {
                respond(ok, HttpStatusCode.OK, json)
            }
        })
        val r = gateway.complete(request)
        assertEquals(listOf(300, 600), seen)
        assertEquals("去北边。", r.text)
        assertEquals(100, r.inputTokens)
        assertEquals(305, r.outputTokens)

        // 翻倍了还是空的：算失败，不再重试
        val hopeless = openai(MockEngine {
            respond("""{"choices":[{"message":{"content":""},"finish_reason":"length"}],"usage":{"prompt_tokens":5,"completion_tokens":5}}""", HttpStatusCode.OK, json)
        })
        assertFalse(assertFailsWith<AiProviderException> { hopeless.complete(request) }.retryable)
    }

    @Test
    fun `截断能认出来：openai 的 length、anthropic 的 max_tokens，整段和流式都行`() = runBlocking {
        val cut = openai(MockEngine {
            respond("""{"choices":[{"message":{"content":"先去北边，再"},"finish_reason":"length"}],"usage":{"prompt_tokens":5,"completion_tokens":5}}""", HttpStatusCode.OK, json)
        })
        assertTrue(cut.complete(request).truncated)
        assertFalse(openai(MockEngine { respond(ok, HttpStatusCode.OK, json) }).complete(request).truncated)

        val cutStream = openai(MockEngine {
            respond(
                "data: {\"choices\":[{\"delta\":{\"content\":\"先去北边\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n",
                HttpStatusCode.OK, sse,
            )
        })
        assertTrue(cutStream.stream(request) { }.truncated)

        val anthropic = AnthropicProvider("https://api.anthropic.com", "k", "claude", HttpClient(MockEngine {
            respond("""{"model":"claude","content":[{"type":"text","text":"先去北边"}],"stop_reason":"max_tokens","usage":{"input_tokens":5,"output_tokens":5}}""", HttpStatusCode.OK, json)
        }))
        assertTrue(anthropic.complete(request).truncated)
        val anthropicStream = AnthropicProvider("https://api.anthropic.com", "k", "claude", HttpClient(MockEngine {
            respond(
                """
                data: {"type":"message_start","message":{"model":"claude","usage":{"input_tokens":5}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"先去北边"}}

                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":5}}

                """.trimIndent() + "\n",
                HttpStatusCode.OK, sse,
            )
        }))
        assertTrue(anthropicStream.stream(request) { }.truncated)
    }

    @Test
    fun `服务商没报用量时按字数估，每月额度照样算得上`() {
        val r = AiResult("去北边那片海。", 0, 0, "m").withUsage(request)
        assertEquals((request.system.length + "周六去哪？".length) / 2, r.inputTokens)
        assertEquals("去北边那片海。".length, r.outputTokens)
        val reported = AiResult("x", 7, 3, "m")
        assertEquals(reported, reported.withUsage(request))
    }
}
