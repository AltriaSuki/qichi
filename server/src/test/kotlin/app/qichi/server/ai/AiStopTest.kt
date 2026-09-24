package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.MessagePage
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P10-04：问 AI 可以中途停下。 */
class AiStopTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    /** 先吐一段字，然后一直等（直到被取消）；[started] 在吐出第一段后完成。 */
    private class HangingGateway(private val first: String) : AiGateway {
        override val model = "fake-hang"
        val started = CompletableDeferred<Unit>()
        override suspend fun complete(request: AiRequest) = error("应该走流式")
        override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult {
            onText(first)
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))

    @Test
    fun `生成中停下：存下已经写出来的部分，标记已停下，不带动作；两个人看到同一条`() {
        val gateway = HangingGateway("可以带一套练功服 [1]，再配一盒\n<acti")
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "国庆带什么给妈妈？"))
            coroutineScope {
                val worker = launch { ctx.jobs.drain() }
                withTimeout(5_000) { gateway.started.await() }
                chi.post("/api/v1/rooms/$room/ai/jobs/$jobId/stop").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
                val res = aqi.post("/api/v1/rooms/$room/ai/jobs/$jobId/stop")
                assertEquals(HttpStatusCode.OK, res.status)
                withTimeout(5_000) { worker.join() }
            }
            for (who in listOf(aqi, chi)) {
                val answer = who.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.first()
                assertEquals(jobId, answer.id)
                assertTrue(answer.aiStopped)
                assertEquals("可以带一套练功服，再配一盒", answer.body, "没写完的动作段、没有对应资料的引用去掉")
            }
            val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Done, job.status)
            assertTrue(job.outputTokens > 0, "停下的也记用量（按字数估）")
            // 已经结束的再停：原样返回
            assertEquals(AiJobAccepted(jobId, AiJobStatus.Done), aqi.post("/api/v1/rooms/$room/ai/jobs/$jobId/stop").body<AiJobAccepted>())
        }
    }

    @Test
    fun `排队时停下：写一条正文为空的已停下消息，之后不再回答`() {
        val gateway = HangingGateway("不该出现")
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "周六去哪？"))
            assertEquals(AiJobAccepted(jobId, AiJobStatus.Done), aqi.post("/api/v1/rooms/$room/ai/jobs/$jobId/stop").body<AiJobAccepted>())
            ctx.jobs.drain()
            assertFalse(gateway.started.isCompleted, "停下后不再调用模型")
            val answer = aqi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.single()
            assertEquals(jobId, answer.id)
            assertTrue(answer.aiStopped)
            assertEquals("", answer.body)
            assertEquals("周六去哪？", answer.aiPrompt)
        }
    }

    @Test
    fun `非成员 404，不存在的任务 404`() {
        val ctx = testContext(clock = clock, aiGateway = HangingGateway("x"))
        serverTest(ctx) { client ->
            val api = Api(client)
            val (aqi, _, room) = api.pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "周六去哪？"))
            api.outsider(aqi).post("/api/v1/rooms/$room/ai/jobs/$jobId/stop").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            aqi.post("/api/v1/rooms/$room/ai/jobs/${UuidV7.generate()}/stop").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        }
    }
}
