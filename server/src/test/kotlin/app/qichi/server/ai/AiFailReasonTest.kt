package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.WsEvent
import app.qichi.shared.model.AiFailReason
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** P16-08：AI 失败时记下种类，随任务状态和 ai.done 发出，App 据此说清原因。 */
class AiFailReasonTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway()
    private val clock = MutableClock()

    @Test
    fun `各种失败原因写进任务并随 ai_done 发出；重试成功后清掉`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, roomId) = Api(client).pair()
            val cases = listOf(
                AiProviderException("余额不足", retryable = false, reason = AiFailReason.Quota) to AiFailReason.Quota,
                AiProviderException("太长", retryable = false, reason = AiFailReason.TooLong) to AiFailReason.TooLong,
                AiProviderException("401", retryable = false) to AiFailReason.Provider,
            )
            for ((failure, expected) in cases) {
                val jobId = UuidV7.generate()
                gateway.failures += failure
                aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, "在吗"))
                ctx.jobs.drain()
                val job = aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>()
                assertEquals(AiJobStatus.Failed, job.status)
                assertEquals(expected.wireName, job.failReason)
            }

            // ai.done 带着原因
            val ws = createClient { install(ClientWebSockets) }
            suspend fun DefaultClientWebSocketSession.next(): WsEvent? = withTimeoutOrNull(5_000) {
                QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            }
            val jobId = UuidV7.generate()
            var done: WsEvent.AiDone? = null
            ws.webSocket("/api/v1/ws", request = { bearerAuth(aqi.tokens.accessToken) }) {
                next() // hello
                gateway.failures += AiProviderException("连接模型服务失败", retryable = false, reason = AiFailReason.Unreachable)
                aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, "在吗"))
                ctx.jobs.drain()
                while (true) {
                    val e = next() ?: break
                    if (e is WsEvent.AiDone && e.jobId == jobId) {
                        done = e
                        break
                    }
                }
            }
            assertEquals(AiJobStatus.Failed.wireName, done?.status)
            assertEquals(AiFailReason.Unreachable.wireName, done?.reason)

            // 点「重试」：成功后原因清掉
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, "在吗"))
            ctx.jobs.drain()
            val retried = aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Done, retried.status)
            assertNull(retried.failReason)
        }
    }

    @Test
    fun `服务商的报错按状态码和关键词分类；余额用完的 429 不再重试`() {
        assertEquals(AiFailReason.Quota, failReason(HttpStatusCode.PaymentRequired, "{}"))
        assertEquals(AiFailReason.Quota, failReason(HttpStatusCode.TooManyRequests, """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(AiFailReason.TooLong, failReason(HttpStatusCode.BadRequest, """{"error":{"code":"context_length_exceeded"}}"""))
        assertEquals(AiFailReason.TooLong, failReason(HttpStatusCode.BadRequest, """{"error":{"message":"prompt is too long: 250000 tokens"}}"""))
        assertEquals(AiFailReason.Provider, failReason(HttpStatusCode.TooManyRequests, """{"error":"rate limit"}"""))
        assertEquals(AiFailReason.Provider, failReason(HttpStatusCode.Unauthorized, """{"error":"invalid api key"}"""))
    }
}
