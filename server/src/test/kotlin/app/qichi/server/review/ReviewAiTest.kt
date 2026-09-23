package app.qichi.server.review

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.ai.FakeGateway
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiReviewFindingsRequest
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.ConvertFindingRequest
import app.qichi.shared.api.CreateReviewRequest
import app.qichi.shared.api.CreateReviewVersionRequest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewAiTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val clock = MutableClock()
    private val gateway = FakeGateway()
    private val ctx by lazy { testContext(clock = clock, aiGateway = gateway) }

    private val v1 = TestPdf.pages(
        listOf("Payment: 30 percent upfront, rest on delivery", "Unit price for cabinets is 1200 per meter"),
        listOf("Upfront payment is 40 percent of the total"),
    )

    private suspend fun Session.review(room: UUID, bytes: ByteArray = v1): Pair<ReviewDocument, UUID> {
        val file = upload(room, bytes, fileName = "quote.pdf", kind = "review", contentType = "application/pdf").body<FileMeta>()
        val versionId = UuidV7.generate()
        val doc = post("/api/v1/rooms/$room/reviews", CreateReviewRequest(UuidV7.generate(), "报价方案", CreateReviewVersionRequest(versionId, file.id))).body<ReviewDocument>()
        return doc to versionId
    }

    private suspend fun Session.findings(room: UUID) = get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().aiFindings

    private fun answer(vararg findings: String) = "好的，下面是我找到的问题：\n[" + findings.joinToString(",") + "]\n以上。"

    private val contradiction = """{"title":"首付比例前后不一致","body":"第 1 页写 30%，第 2 页写 40%，请核对。",
        "evidence":[{"ref":"p1-b1","quote":"30 percent upfront"},{"ref":"p1-b1","quote":"40 percent of the total"}]}"""
    private val made = """{"title":"编造的问题","body":"x","evidence":[{"ref":"p1-b2","quote":"this sentence is not in the file"}]}"""
    private val price = """{"title":"单价没写币种","body":"","evidence":[{"ref":"p9-b9","quote":"1200 per meter"}]}"""

    @Test fun `授权 AI 审一版：只发文字层，每条发现都带核对过的原文证据；编造的证据丢掉；再审一次不重复`() = serverTest(ctx) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val (doc, versionId) = aqi.review(room)
        val jobId = UuidV7.generate()
        // 预览没好时不能审
        aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(jobId, doc.id, versionId)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        ctx.jobs.drain()

        gateway.answer = answer(contradiction, made, price)
        val r = aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(jobId, doc.id, versionId))
        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        ctx.jobs.drain()

        val prompt = gateway.requests.single().messages.single().content
        assertTrue(prompt.contains("[p1-b2] Unit price for cabinets is 1200 per meter"), prompt)
        assertTrue(prompt.contains("[p2-b1] Upfront payment is 40 percent of the total"))
        assertTrue(prompt.contains("报价方案（第 1 版）"))

        val found = chi.findings(room).sortedBy { it.title }
        assertEquals(listOf("单价没写币种", "首付比例前后不一致"), found.map { it.title })
        val c = found.single { it.title == "首付比例前后不一致" }
        // 编号写错的证据按原话在全文里找到正确位置
        assertEquals(listOf("p1-b1" to 1, "p2-b1" to 2), c.evidence.map { it.ref to it.page })
        assertEquals("p1-b2", found.single { it.title == "单价没写币种" }.evidence.single().ref)
        assertTrue(found.all { it.status == FindingStatus.New && it.jobId == jobId && it.requestedBy == aqi.userId() })
        val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
        assertEquals(AiJobStatus.Done, job.status)
        assertEquals("ai_finding:2", job.resultRef)

        // 再审一次：已经记下的告诉 AI，不重复
        val again = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(again, doc.id, versionId))
        ctx.jobs.drain()
        assertTrue(gateway.requests.last().messages.single().content.contains("- 首付比例前后不一致"))
        assertEquals(2, aqi.findings(room).size)
    }

    @Test fun `AI 回答不是 JSON 也不会出错：一条都没有；没开 AI 返回 503`() {
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val (doc, versionId) = aqi.review(room)
            ctx.jobs.drain()
            gateway.answer = "我看不出什么问题。"
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(jobId, doc.id, versionId))
            ctx.jobs.drain()
            assertEquals(AiJobStatus.Done, aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().status)
            assertTrue(aqi.findings(room).isEmpty())
        }
        TestDatabase.reset()
        val noAi = testContext(clock = clock)
        serverTest(noAi) { client ->
            val (aqi, _, room) = Api(client).pair()
            val (doc, versionId) = aqi.review(room)
            noAi.jobs.drain()
            aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(UuidV7.generate(), doc.id, versionId))
                .assertProblem(HttpStatusCode.ServiceUnavailable, ProblemCode.AiUnavailable)
        }
    }

    @Test fun `转为人工批注：作者是自己、钉在第一条证据上；再转返回同一条；忽略`() = serverTest(ctx) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val (doc, versionId) = aqi.review(room)
        ctx.jobs.drain()
        gateway.answer = answer(contradiction, price)
        aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(UuidV7.generate(), doc.id, versionId))
        ctx.jobs.drain()
        val found = aqi.findings(room)
        val c = found.single { it.title == "首付比例前后不一致" }
        val p = found.single { it.title == "单价没写币种" }

        val annotationId = UuidV7.generate()
        val r = chi.post("/api/v1/rooms/$room/ai-findings/${c.id}/convert", ConvertFindingRequest(annotationId))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val ann = r.body<Annotation>()
        assertEquals(annotationId, ann.id)
        assertEquals(chi.userId(), ann.authorId)
        assertEquals(versionId, ann.versionId)
        assertEquals("p1-b1", ann.anchor.ref)
        assertEquals(AnchorKind.Paragraph, ann.anchor.kind)
        assertEquals("30 percent upfront", ann.anchor.quote)
        assertEquals("首付比例前后不一致\n第 1 页写 30%，第 2 页写 40%，请核对。", ann.body)
        val again = aqi.post("/api/v1/rooms/$room/ai-findings/${c.id}/convert", ConvertFindingRequest(UuidV7.generate()))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(annotationId, again.body<Annotation>().id)

        val dismissed = aqi.post("/api/v1/rooms/$room/ai-findings/${p.id}/dismiss").body<AiFinding>()
        assertEquals(FindingStatus.Dismissed, dismissed.status)
        val after = aqi.findings(room).associateBy { it.id }
        assertEquals(FindingStatus.Converted, after[c.id]!!.status)
        assertEquals(annotationId, after[c.id]!!.convertedAnnotationId)

        val stranger = Api(client).outsider(aqi)
        stranger.post("/api/v1/rooms/$room/ai-findings/${p.id}/dismiss").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `新版本：原文还在的发现带过去，原文没了的在旧的上面记下；处理过的不带`() = serverTest(ctx) { client ->
        val (aqi, _, room) = Api(client).pair()
        val (doc, versionId) = aqi.review(room)
        ctx.jobs.drain()
        val dup = """{"title":"重复写了交货","body":"","evidence":[{"ref":"p1-b1","quote":"rest on delivery"}]}"""
        gateway.answer = answer(contradiction, price, dup)
        aqi.post("/api/v1/rooms/$room/ai/review-findings", AiReviewFindingsRequest(UuidV7.generate(), doc.id, versionId))
        ctx.jobs.drain()
        val dupId = aqi.findings(room).single { it.title == "重复写了交货" }.id
        aqi.post("/api/v1/rooms/$room/ai-findings/$dupId/dismiss")

        // v2：首付统一成 40%（30% 那句没了），单价那句还在
        val file = aqi.upload(room, TestPdf.pages(
            listOf("Payment: 40 percent upfront, rest on delivery"),
            listOf("Upfront payment is 40 percent of the total", "Unit price for cabinets is 1200 per meter"),
        ), fileName = "quote-v2.pdf", kind = "review", contentType = "application/pdf").body<FileMeta>()
        val v2 = aqi.post("/api/v1/rooms/$room/reviews/${doc.id}/versions", CreateReviewVersionRequest(UuidV7.generate(), file.id)).body<ReviewVersion>()
        ctx.jobs.drain()

        val all = aqi.findings(room)
        val inV2 = all.filter { it.versionId == v2.id }
        assertEquals(listOf("单价没写币种"), inV2.map { it.title })
        assertEquals(2, inV2.single().evidence.single().page, "单价那句挪到了第 2 页")
        val oldPrice = all.single { it.versionId == versionId && it.title == "单价没写币种" }
        assertEquals(oldPrice.id, inV2.single().carriedFromId)
        assertNull(oldPrice.goneInVersion)
        assertEquals(2, all.single { it.versionId == versionId && it.title == "首付比例前后不一致" }.goneInVersion)
    }
}
