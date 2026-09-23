package app.qichi.server.summaries

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.ai.FakeGateway
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreateSummaryRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.Summary
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.SummaryKind
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway().apply { answer = "## 这一周\n周三定下了去海边 [2]，阿栖记下想种柠檬树 [1]。" }
    // 上海时间 2026-09-23（周三）12:00
    private val clock = MutableClock(Instant.parse("2026-09-23T04:00:00Z"))

    @Test fun `本周总结：按房间时区的周一到周日；给 AI 编号的素材；只保存正文引用到的来源`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(UuidV7.generate(), "阳台种一棵柠檬树"))
            clock.advance(Duration.ofMinutes(1))
            chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "那就定了，周六去海边"))
            clock.advance(Duration.ofMinutes(1))
            chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "带上相机"))

            val jobId = UuidV7.generate()
            val accepted = aqi.post("/api/v1/rooms/$room/summaries", CreateSummaryRequest(jobId, SummaryKind.Week))
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertEquals(AiJobStatus.Queued, accepted.body<AiJobAccepted>().status)
            ctx.jobs.drain()

            val prompt = gateway.requests.single().messages.single().content
            assertTrue(prompt.contains("2026年9月21日—2026年9月27日"), prompt)
            assertTrue(prompt.contains("[1] 9月23日 · aqi · 灵感：阳台种一棵柠檬树"))
            assertTrue(prompt.contains("[3] 9月23日 · xiaochi · 聊天：带上相机"))

            val summary = chi.get("/api/v1/rooms/$room/summaries").body<List<Summary>>().single()
            assertEquals(jobId, summary.id)
            assertEquals(LocalDate.of(2026, 9, 21) to LocalDate.of(2026, 9, 27), summary.rangeStart to summary.rangeEnd)
            assertEquals(listOf(1, 2), summary.sources.map { it.number })
            assertEquals(listOf("idea", "message"), summary.sources.map { it.type })
            assertTrue(summary.aiDerived)
            assertFalse(summary.locked)

            // 普通总结可以删，进回收站
            chi.delete("/api/v1/rooms/$room/summaries/${summary.id}")
            assertEquals(listOf(TrashType.Summary), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        }
    }

    @Test fun `参数：不能手动生成年度回顾；自定义范围不能倒着、不能超过一年；没内容时不调用 AI`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val path = "/api/v1/rooms/$room/summaries"
            aqi.post(path, CreateSummaryRequest(UuidV7.generate(), SummaryKind.Year)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            aqi.post(path, CreateSummaryRequest(UuidV7.generate(), SummaryKind.Custom, rangeStart = LocalDate.of(2026, 9, 2), rangeEnd = LocalDate.of(2026, 9, 1)))
                .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            aqi.post(path, CreateSummaryRequest(UuidV7.generate(), SummaryKind.Custom, rangeStart = LocalDate.of(2025, 1, 1), rangeEnd = LocalDate.of(2026, 9, 1)))
                .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            aqi.post(path, CreateSummaryRequest(UuidV7.generate(), SummaryKind.Month, anchor = LocalDate.of(2026, 2, 10)))
            ctx.jobs.drain()
            assertTrue(gateway.requests.isEmpty())
            val s = aqi.get(path).body<List<Summary>>().single()
            assertEquals(LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28), s.rangeStart to s.rangeEnd)
            assertTrue(s.body.contains("没有记下什么"))
        }
    }

    @Test fun `年度回顾：1 月 1 日之后自动生成上一年的，锁定不能删，不会重复生成`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val api = Api(client)
            val (first, _, room) = api.pair()
            var aqi = first
            aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(UuidV7.generate(), "明年一起学做饭"))
            // 年内检查：还没到明年，什么都不做
            ctx.ai.ensureYearlyCheck()
            ctx.jobs.drain()
            assertTrue(aqi.get("/api/v1/rooms/$room/summaries").body<List<Summary>>().isEmpty())

            clock.advance(Duration.ofDays(101)) // 2027-01-02
            ctx.ai.ensureYearlyCheck()
            ctx.jobs.drain()
            clock.advance(Duration.ofHours(7))
            ctx.jobs.drain()
            aqi = api.loginOk("aqi") // 过了很久，重新登录
            val years = aqi.get("/api/v1/rooms/$room/summaries").body<List<Summary>>().filter { it.kind == SummaryKind.Year }
            assertEquals(1, years.size, "再检查一次也不会多出一份")
            val year = years.single()
            assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31), year.rangeStart to year.rangeEnd)
            assertTrue(year.locked)
            assertEquals(null, year.requestedBy)
            aqi.delete("/api/v1/rooms/$room/summaries/${year.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        }
    }
}
