package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiWriteRequest
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.DraftGenre
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.WriteAssistMode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P9-04 / P9-05：写作助手。 */
class WriteAssistTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway()
    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))

    @Test fun `润色：结果只给发起的人看，不写进文稿；标题带进提示词`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val doc = aqi.post("/api/v1/rooms/$room/documents", CreateDocumentRequest(UuidV7.generate(), "海边周末")).body<Document>()
            gateway.answer = "周六一早八点，我们出发去东山岛。"
            val jobId = UuidV7.generate()
            val accepted = aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(jobId, WriteAssistMode.Polish, " 周六早上八点我们就出发去东山岛了 ", doc.id))
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            ctx.jobs.drain()

            val sent = gateway.requests.single().messages.single().content
            assertTrue(sent.contains("文稿标题：海边周末"), sent)
            assertTrue(sent.contains("周六早上八点我们就出发去东山岛了"))
            val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobKind.WriteAssist, job.kind)
            assertEquals(AiJobStatus.Done, job.status)
            assertEquals("周六一早八点，我们出发去东山岛。", job.resultText)
            assertNull(chi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().resultText, "对方看不到")
            assertTrue(aqi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.isEmpty(), "不产生任何消息")
        }
    }

    @Test fun `起草稿：参考那段时间的房间资料；不在范围里的不给`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "东山岛的日落好美"))
            aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(UuidV7.generate(), "下次带三脚架"))
            gateway.answer = "```markdown\n# 东山岛的周末\n\n那天的日落……\n```"
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(jobId, WriteAssistMode.Draft, genre = DraftGenre.Travel,
                rangeStart = LocalDate.parse("2026-09-20"), rangeEnd = LocalDate.parse("2026-09-24")))
            ctx.jobs.drain()
            val sent = gateway.requests.single().messages.single().content
            assertTrue(sent.contains("游记"), sent)
            assertTrue(sent.contains("东山岛的日落好美") && sent.contains("下次带三脚架"), sent)
            assertTrue(sent.contains("现在是 2026年9月24日"))
            assertEquals("# 东山岛的周末\n\n那天的日落……", aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().resultText, "去掉代码块标记")

            val early = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(early, WriteAssistMode.Draft, genre = DraftGenre.Review,
                rangeStart = LocalDate.parse("2026-08-01"), rangeEnd = LocalDate.parse("2026-08-07")))
            ctx.jobs.drain()
            val sentEarly = gateway.requests.last().messages.single().content
            assertFalse(sentEarly.contains("东山岛的日落好美"))
            assertTrue(sentEarly.contains("这段时间没有记下什么"))
        }
    }

    @Test fun `校验：没选文字、范围超过 31 天、别的房间的文稿都不行`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val api = Api(client)
            val (aqi, _, room) = api.pair()
            val path = "/api/v1/rooms/$room/ai/write-assist"
            aqi.post(path, AiWriteRequest(UuidV7.generate(), WriteAssistMode.Proofread, "  ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            aqi.post(path, AiWriteRequest(UuidV7.generate(), WriteAssistMode.Draft, genre = DraftGenre.Letter,
                rangeStart = LocalDate.parse("2026-08-01"), rangeEnd = LocalDate.parse("2026-09-24"))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            val otherRoom = aqi.createRoom("另一个").room.id
            val foreign = aqi.post("/api/v1/rooms/$otherRoom/documents", CreateDocumentRequest(UuidV7.generate(), "别处")).body<Document>()
            aqi.post(path, AiWriteRequest(UuidV7.generate(), WriteAssistMode.Titles, "正文", foreign.id)).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            api.outsider(aqi).post(path, AiWriteRequest(UuidV7.generate(), WriteAssistMode.Polish, "x")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        }
    }
}
