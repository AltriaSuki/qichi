package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.WsEvent
import app.qichi.shared.util.UuidV7
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.bearerAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** P8-03：问 AI 边生成边显示。 */
class AiStreamTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val sse = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    private val request = AiRequest("你是助手", listOf(AiMessage(AiMessage.Role.User, "周六去哪？")), maxTokens = 300)
    private fun HttpRequestData.bodyJson() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `openai 兼容：按流式请求，一段段拼起来，最后带用量`() = runBlocking {
        var seen: HttpRequestData? = null
        val engine = MockEngine { req ->
            seen = req
            respond(
                """
                data: {"model":"gpt-6-sol","choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"去北边"}}]}

                data: {"choices":[{"delta":{"content":"那片海。"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8}}

                data: [DONE]

                """.trimIndent(),
                HttpStatusCode.OK, sse,
            )
        }
        val seenTexts = mutableListOf<String>()
        val result = OpenAiCompatibleProvider("https://api.example.com/v1", "sk", "gpt-6-sol", HttpClient(engine)).stream(request) { seenTexts += it }
        assertEquals(listOf("去北边", "去北边那片海。"), seenTexts)
        assertEquals(AiResult("去北边那片海。", 120, 8, "gpt-6-sol"), result)
        val body = seen!!.bodyJson()
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai 兼容：服务商不支持流式、照常整段回，也能用`() = runBlocking {
        val engine = MockEngine {
            respond("""{"model":"m","choices":[{"message":{"content":"整段"}}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val seenTexts = mutableListOf<String>()
        val result = OpenAiCompatibleProvider("https://api.example.com/v1", "sk", "m", HttpClient(engine)).stream(request) { seenTexts += it }
        assertEquals(listOf("整段"), seenTexts)
        assertEquals("整段", result.text)
    }

    @Test
    fun `anthropic：文字增量拼起来，用量从开头和结尾的事件里取`() = runBlocking {
        val engine = MockEngine {
            respond(
                """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-x","usage":{"input_tokens":50}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"周六"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"见。"}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":4}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
                HttpStatusCode.OK, sse,
            )
        }
        val seenTexts = mutableListOf<String>()
        val result = AnthropicProvider("https://api.anthropic.com", "k", "claude-x", HttpClient(engine)).stream(request) { seenTexts += it }
        assertEquals(listOf("周六", "周六见。"), seenTexts)
        assertEquals(AiResult("周六见。", 50, 4, "claude-x"), result)
    }

    @Test
    fun `流式时给人看的部分：去掉动作段（包括一半的开头）和引用编号`() {
        assertEquals("周六八点出发。", AiActionParser.visiblePart("周六八点出发 [1]。\n<actions>\n[{\"kind\":"))
        assertEquals("周六八点出发。", AiActionParser.visiblePart("周六八点出发。\n<act"))
        assertEquals("预算两千", AiActionParser.visiblePart("预算两千[1"))
        assertEquals("", AiActionParser.visiblePart("<actions>"))
    }

    /** 分几段吐字的假模型，每段之间停一下（服务端最多每 250 毫秒推一次）。 */
    private class StreamingGateway(private val pieces: List<String>) : AiGateway {
        override val model = "fake-stream"
        override suspend fun complete(request: AiRequest) = error("应该走流式")
        override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult {
            val sb = StringBuilder()
            pieces.forEach { sb.append(it); onText(sb.toString()); delay(300) }
            return AiResult(sb.toString(), 10, 5, model)
        }
    }

    @Test
    fun `问 AI：带 caps=ai_stream 的连接一段段收到回答，旧版连接收不到；最后仍是一条完整的 AI 消息`() {
        val gateway = StreamingGateway(listOf("周六去", "北边那片海 [1]，", "人少。", "\n<actions>[]</actions>"))
        val ctx = testContext(clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z")), aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val ws = createClient { install(ClientWebSockets) }
            suspend fun DefaultClientWebSocketSession.next(): WsEvent? = withTimeoutOrNull(5_000) {
                QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            }
            suspend fun DefaultClientWebSocketSession.untilDone(): List<WsEvent> = buildList {
                while (true) {
                    val e = next() ?: break
                    add(e)
                    if (e is WsEvent.AiDone) break
                }
            }

            val jobId = UuidV7.generate()
            ws.webSocket("/api/v1/ws?caps=notify,ai_stream", request = { bearerAuth(chi.tokens.accessToken) }) {
                next() // hello
                aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "周六去哪片海？"))
                ctx.jobs.drain()
                val deltas = untilDone().filterIsInstance<WsEvent.AiDelta>()
                assertTrue(deltas.size >= 3, deltas.toString())
                assertTrue(deltas.all { it.jobId == jobId && it.roomId == room })
                assertEquals("周六去", deltas.first().text)
                assertEquals("周六去北边那片海，人少。", deltas.last().text, "不带引用编号和动作段")
            }
            val answer = aqi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.first()
            assertEquals(jobId, answer.id)
            assertEquals("周六去北边那片海，人少。", answer.body, "没有对应资料的 [1] 去掉，动作段不进正文")

            ws.webSocket("/api/v1/ws", request = { bearerAuth(chi.tokens.accessToken) }) {
                next()
                aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(UuidV7.generate(), "再问一次"))
                ctx.jobs.drain()
                assertTrue(untilDone().none { it is WsEvent.AiDelta }, "旧版 App 不认识 ai.delta")
            }
        }
    }
}
