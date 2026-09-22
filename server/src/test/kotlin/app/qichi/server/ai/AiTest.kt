package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.jobs.JobQueue
import app.qichi.server.serverTest
import app.qichi.server.testConfig
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiUsage
import app.qichi.shared.api.Me
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可控的假模型：记下收到的请求，按预设返回或失败。 */
class FakeGateway : AiGateway {
    override val model = "fake-model"
    val requests = mutableListOf<AiRequest>()
    val failures = ArrayDeque<AiProviderException>()
    var answer = "去北边那片海，人少。"

    override suspend fun complete(request: AiRequest): AiResult {
        requests += request
        failures.removeFirstOrNull()?.let { throw it }
        return AiResult(answer, inputTokens = 100, outputTokens = 20, model = model)
    }
}

class AiTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway()
    private val clock = MutableClock()

    private suspend fun Session.ask(roomId: UUID, prompt: String, jobId: UUID = UuidV7.generate()) =
        post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, prompt))

    private suspend fun Session.send(roomId: UUID, body: String): Message =
        post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", body)).body()

    @Test
    fun `没配置 AI：接口 503，me 里 aiEnabled 为 false`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        assertFalse(aqi.get("/api/v1/me").body<Me>().aiEnabled)
        aqi.ask(roomId, "周六去哪？").assertProblem(HttpStatusCode.ServiceUnavailable, ProblemCode.AiUnavailable)
    }

    @Test
    fun `问 AI：立即 202；后台带上最近聊天回答，写成 id 等于 jobId 的 AI 消息`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            assertTrue(aqi.get("/api/v1/me").body<Me>().aiEnabled)
            xiaochi.send(roomId, "周六早上出发怎么样？")
            val secret = aqi.send(roomId, "发错了")
            aqi.post("/api/v1/rooms/$roomId/messages/${secret.id}/retract")
            aqi.send(roomId, "好呀")

            val jobId = UuidV7.generate()
            val response = aqi.ask(roomId, "  周六适合去哪片海？ ", jobId)
            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            assertEquals(AiJobAccepted(jobId, AiJobStatus.Queued), response.body<AiJobAccepted>())
            assertEquals(AiJobStatus.Queued, aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>().status)

            ctx.jobs.drain()

            val sent = gateway.requests.single()
            val user = sent.messages.single().content
            assertTrue(user.contains("xiaochi：周六早上出发怎么样？"), user)
            assertTrue(user.contains("aqi：好呀"))
            assertFalse(user.contains("发错了"), "撤回的消息不给 AI")
            assertTrue(user.contains("周六适合去哪片海？"))
            assertTrue(sent.system.isNotBlank())

            val answer = xiaochi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.first()
            assertEquals(jobId, answer.id)
            assertEquals(MessageKind.Ai, answer.kind)
            assertNull(answer.authorId)
            assertEquals("去北边那片海，人少。", answer.body)
            assertEquals("周六适合去哪片海？", answer.aiPrompt)

            val job = aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Done, job.status)
            assertEquals(AiJobKind.ChatAnswer, job.kind)
            assertEquals("fake-model", job.model)
            assertEquals(100, job.inputTokens)
            assertEquals("message:$jobId", job.resultRef)

            // 同一 jobId 再请求：返回当前状态，不会再问一次
            assertEquals(AiJobStatus.Done, aqi.ask(roomId, "周六适合去哪片海？", jobId).body<AiJobAccepted>().status)
            ctx.jobs.drain()
            assertEquals(1, gateway.requests.size)
            // 别人不能用这个 jobId
            xiaochi.ask(roomId, "x", jobId).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)
        }
    }

    @Test
    fun `失败：可重试的先退避再试；最后失败标记 failed，同一 jobId 可以重新排队`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, roomId) = Api(client).pair()
            val jobId = UuidV7.generate()

            gateway.failures += AiProviderException("429", retryable = true)
            gateway.failures += AiProviderException("401", retryable = false)
            aqi.ask(roomId, "在吗", jobId)
            ctx.jobs.drain()
            assertEquals(AiJobStatus.Running, aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>().status, "等着重试")
            clock.advance(JobQueue.backoff(1))
            ctx.jobs.drain()
            val failed = aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Failed, failed.status)
            assertEquals("没有得到回答", failed.error)
            assertTrue(aqi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.isEmpty())

            // 用户点「重试」：同一 jobId 重新排队
            assertEquals(AiJobStatus.Queued, aqi.ask(roomId, "在吗", jobId).body<AiJobAccepted>().status)
            ctx.jobs.drain()
            assertEquals(AiJobStatus.Done, aqi.get("/api/v1/rooms/$roomId/ai/jobs/$jobId").body<AiJob>().status)
            assertEquals(jobId, aqi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.single().id)
        }
    }

    @Test
    fun `每月额度用完返回 429；我发起的 AI 使用按月列出`() {
        val config = testConfig().let { it.copy(ai = it.ai.copy(monthlyTokenLimit = 150)) }
        val ctx = testContext(config = config, clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            aqi.ask(roomId, "第一个问题")
            ctx.jobs.drain()
            // 已用 120，未超过 150：还能问
            assertEquals(HttpStatusCode.Accepted, xiaochi.ask(roomId, "第二个问题").status)
            ctx.jobs.drain()
            aqi.ask(roomId, "第三个问题").assertProblem(HttpStatusCode.TooManyRequests, ProblemCode.AiQuotaExceeded)

            val usage = aqi.get("/api/v1/me/ai-usage").body<AiUsage>()
            assertEquals(1, usage.jobs.size, "只列我发起的")
            assertEquals(100L, usage.myInputTokens)
            assertEquals(20L, usage.myOutputTokens)
            assertEquals(240L, usage.monthUsedTokens)
            assertEquals(150L, usage.monthLimitTokens)
            assertTrue(aqi.get("/api/v1/me/ai-usage?month=2020-01").body<AiUsage>().jobs.isEmpty())
            aqi.get("/api/v1/me/ai-usage?month=abc").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        }
    }

    @Test
    fun `参数与权限：空问题 400，非成员 404`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val api = Api(client)
            val (aqi, _, roomId) = api.pair()
            aqi.ask(roomId, "   ").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            aqi.ask(roomId, "字".repeat(2001)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            val outsider = api.outsider(aqi)
            outsider.ask(roomId, "hi").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.get("/api/v1/rooms/$roomId/ai/jobs/${UuidV7.generate()}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        }
    }
}
