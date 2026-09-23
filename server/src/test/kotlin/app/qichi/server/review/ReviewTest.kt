package app.qichi.server.review

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateAnnotationReplyRequest
import app.qichi.shared.api.CreateAnnotationRequest
import app.qichi.shared.api.CreateReviewRequest
import app.qichi.shared.api.CreateReviewVersionRequest
import app.qichi.shared.api.DiffKind
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.NormRect
import app.qichi.shared.api.Patch
import app.qichi.shared.api.ReviewDiff
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateAnnotationRequest
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.ReviewFormat
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 测试用的 PDF：每页若干段落（每段一行或几行），可选画一张图。 */
object TestPdf {
    fun pages(vararg pages: List<String>, imageOnPage: Int? = null): ByteArray = PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        pages.forEachIndexed { index, paragraphs ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                var y = 780f
                for (p in paragraphs) {
                    // 长段落按 60 个字符一行折开，行距 14pt；段落之间空 28pt
                    for (line in p.chunked(60)) {
                        cs.beginText()
                        cs.setFont(font, 12f)
                        cs.newLineAtOffset(72f, y)
                        cs.showText(line)
                        cs.endText()
                        y -= 14f
                    }
                    y -= 28f
                }
                if (imageOnPage == index + 1) {
                    val img = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
                    cs.drawImage(LosslessFactory.createFromImage(doc, img), 300f, 200f, 200f, 150f)
                }
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** 表格：每行的单元格在同一条基线上，列宽 150pt。 */
    fun grid(rows: List<List<String>>): ByteArray = PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            rows.forEachIndexed { r, cells ->
                cells.forEachIndexed { c, text ->
                    cs.beginText()
                    cs.setFont(font, 11f)
                    cs.newLineAtOffset(72f + c * 150f, 780f - r * 20f)
                    cs.showText(text)
                    cs.endText()
                }
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** 最简单的 docx：word/document.xml 里一段段文字。 */
    fun docx(vararg paragraphs: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<Types/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(("<w:document><w:body>" + paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" } + "</w:body></w:document>").toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}

/** 假的文档转换：docx 取出文字排成一页 PDF，csv 排成表格；[fail] 可以模拟出错。 */
class FakeConverter : DocumentConverter {
    var fail: Exception? = null
    val calls = mutableListOf<String>()

    override suspend fun toPdf(input: Path, extension: String, output: Path) {
        calls += extension
        fail?.let { throw it }
        val bytes = when (extension) {
            "csv" -> TestPdf.grid(Files.readAllLines(input).map { it.split(',') })
            "docx" -> {
                val xml = java.util.zip.ZipFile(input.toFile()).use { z -> z.getInputStream(z.getEntry("word/document.xml")).readAllBytes().decodeToString() }
                TestPdf.pages(Regex("<w:t>(.*?)</w:t>").findAll(xml).map { it.groupValues[1] }.toList())
            }
            else -> throw PreviewFailure("不认识 $extension")
        }
        Files.write(output, bytes)
    }
}

class ReviewTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val clock = MutableClock()
    private val converter = FakeConverter()
    private val ctx by lazy { testContext(clock = clock, converter = converter) }

    private suspend fun Session.uploadReview(roomId: UUID, bytes: ByteArray, name: String = "quote.pdf"): FileMeta {
        val r = upload(roomId, bytes, fileName = name, kind = "review", contentType = "application/octet-stream")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.body()
    }

    private suspend fun Session.newReview(roomId: UUID, bytes: ByteArray, name: String = "quote.pdf", title: String = "报价方案"): Pair<ReviewDocument, UUID> {
        val file = uploadReview(roomId, bytes, name)
        val versionId = UuidV7.generate()
        val r = post("/api/v1/rooms/$roomId/reviews", CreateReviewRequest(UuidV7.generate(), title, CreateReviewVersionRequest(versionId, file.id)))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.body<ReviewDocument>() to versionId
    }

    private suspend fun Session.newVersion(roomId: UUID, docId: UUID, bytes: ByteArray, name: String = "quote.pdf"): ReviewVersion {
        val file = uploadReview(roomId, bytes, name)
        val r = post("/api/v1/rooms/$roomId/reviews/$docId/versions", CreateReviewVersionRequest(UuidV7.generate(), file.id))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.body()
    }

    private suspend fun Session.pages(roomId: UUID, docId: UUID, v: Int): List<ReviewPage> =
        get("/api/v1/rooms/$roomId/reviews/$docId/versions/$v/pages").body()

    private suspend fun Session.bootstrap(roomId: UUID): Bootstrap = get("/api/v1/rooms/$roomId/bootstrap").body()

    private suspend fun Session.version(roomId: UUID, id: UUID): ReviewVersion = bootstrap(roomId).reviewVersions.single { it.id == id }

    private suspend fun Session.annotate(roomId: UUID, docId: UUID, versionId: UUID, anchor: AnnotationAnchor, body: String = "这里要改", kind: AnnotationKind = AnnotationKind.Comment) =
        post("/api/v1/rooms/$roomId/reviews/$docId/annotations", CreateAnnotationRequest(UuidV7.generate(), versionId, anchor, kind, body))

    private val v1 = TestPdf.pages(
        listOf("Quote for the kitchen renovation", "Unit price for cabinets is 1200 per meter", "Payment: 30 percent upfront"),
        listOf("Delivery within six weeks after signing"),
        imageOnPage = 2,
    )

    @Test fun `上传 PDF 建审稿文件，后台生成预览：每页一张图、段落文字层、图片区域；非成员 404`() = serverTest(ctx) { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val (doc, versionId) = aqi.newReview(room, v1)
        assertEquals(1, doc.latestVersion)
        assertEquals(PreviewStatus.Pending, aqi.version(room, versionId).previewStatus)
        assertTrue(aqi.pages(room, doc.id, 1).isEmpty(), "预览没生成好时是空的")

        ctx.jobs.drain()
        val v = chi.version(room, versionId)
        assertEquals(PreviewStatus.Ready, v.previewStatus)
        assertEquals(2, v.pageCount)
        assertEquals(ReviewFormat.Pdf, v.format)
        assertEquals("quote.pdf", v.fileName)

        val pages = chi.pages(room, doc.id, 1)
        assertEquals(listOf(1, 2), pages.map { it.page })
        assertEquals(595.0, pages[0].width, 1.0)
        assertEquals(listOf("Quote for the kitchen renovation", "Unit price for cabinets is 1200 per meter", "Payment: 30 percent upfront"), pages[0].blocks.map { it.text })
        assertTrue(pages[0].blocks.all { it.kind == AnchorKind.Paragraph && it.rect.w > 0 && it.rect.y in 0.0..0.5 })
        assertEquals("p1-b2", pages[0].blocks[1].id)
        assertEquals(1, pages[1].images.size, "第二页画了一张图")
        pages[1].images.single().let { assertEquals(300.0 / 595.0, it.x, 0.01); assertEquals(200.0 / 595.0, it.w, 0.01) }

        val image = chi.get("/api/v1/files/${pages[0].imageFileId}")
        assertEquals(HttpStatusCode.OK, image.status)
        assertEquals("image/jpeg", image.headers[HttpHeaders.ContentType])
        val decoded = ImageIO.read(image.bodyAsBytes().inputStream())
        assertTrue(decoded.width in 1150..1250, "144 dpi 的 A4 宽约 1190 像素：${decoded.width}")

        val stranger = api.outsider(aqi)
        stranger.get("/api/v1/rooms/$room/reviews").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        stranger.get("/api/v1/rooms/$room/reviews/${doc.id}/versions/1/pages").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        stranger.get("/api/v1/files/${pages[0].imageFileId}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertEquals(listOf(doc.id), aqi.get("/api/v1/rooms/$room/reviews").body<List<ReviewDocument>>().map { it.id })
    }

    @Test fun `Word 和 CSV 经转换服务变成 PDF；CSV 按单元格切开；不支持的文件 415；同 id 重传返回已有的`() = serverTest(ctx) { client ->
        val (aqi, _, room) = Api(client).pair()
        val (doc, wordVersion) = aqi.newReview(room, TestPdf.docx("First paragraph of the contract", "Second paragraph"), name = "合同.docx")
        val (sheet, sheetVersion) = aqi.newReview(room, "Item,Qty,Price\nCabinet,3,1200\nSink,1,800\n".toByteArray(), name = "prices.csv", title = "价格表")
        ctx.jobs.drain()
        assertEquals(listOf("docx", "csv"), converter.calls)

        val word = aqi.version(room, wordVersion)
        assertEquals(ReviewFormat.Text, word.format)
        assertEquals(PreviewStatus.Ready, word.previewStatus)
        assertEquals(listOf("First paragraph of the contract", "Second paragraph"), aqi.pages(room, doc.id, 1).single().blocks.map { it.text })

        assertEquals(ReviewFormat.Sheet, aqi.version(room, sheetVersion).format)
        val cells = aqi.pages(room, sheet.id, 1).single().blocks
        assertTrue(cells.all { it.kind == AnchorKind.Cell })
        assertEquals(listOf("Item", "Qty", "Price", "Cabinet", "3", "1200", "Sink", "1", "800"), cells.map { it.text })

        aqi.upload(room, "just text".toByteArray(), fileName = "a.bin", kind = "review", contentType = "application/octet-stream")
            .assertProblem(HttpStatusCode.UnsupportedMediaType, ProblemCode.UnsupportedMediaType)
        // 审稿文件只能用 kind = review 传的文件
        val plain = aqi.upload(room, v1, fileName = "a.pdf", kind = "file", contentType = "application/pdf").body<FileMeta>()
        aqi.post("/api/v1/rooms/$room/reviews", CreateReviewRequest(UuidV7.generate(), "x", CreateReviewVersionRequest(UuidV7.generate(), plain.id)))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val file = aqi.uploadReview(room, v1)
        val req = CreateReviewRequest(UuidV7.generate(), "报价", CreateReviewVersionRequest(UuidV7.generate(), file.id))
        assertEquals(HttpStatusCode.Created, aqi.post("/api/v1/rooms/$room/reviews", req).status)
        assertEquals(HttpStatusCode.OK, aqi.post("/api/v1/rooms/$room/reviews", req).status)
    }

    @Test fun `转换失败：转不了直接标失败；暂时出错会重试，最后一次还不行才标失败；没配转换服务时只能预览 PDF`() = serverTest(ctx) { client ->
        val (aqi, _, room) = Api(client).pair()
        converter.fail = PreviewFailure("这个文件转换不了，可以另存为 PDF 再传")
        val (_, bad) = aqi.newReview(room, TestPdf.docx("x"), name = "a.docx")
        ctx.jobs.drain()
        aqi.version(room, bad).let {
            assertEquals(PreviewStatus.Failed, it.previewStatus)
            assertEquals("这个文件转换不了，可以另存为 PDF 再传", it.previewError)
        }

        converter.fail = IllegalStateException("转换服务暂时连不上")
        val (_, flaky) = aqi.newReview(room, TestPdf.docx("y"), name = "b.docx")
        ctx.jobs.drain()
        assertEquals(PreviewStatus.Pending, aqi.version(room, flaky).previewStatus, "第一次失败后等着重试")
        repeat(2) { clock.advance(Duration.ofMinutes(1)); ctx.jobs.drain() }
        aqi.version(room, flaky).let {
            assertEquals(PreviewStatus.Failed, it.previewStatus)
            assertEquals("预览没能生成，可以稍后重新上传试试", it.previewError)
        }

        // 坏掉的 PDF
        val (_, broken) = aqi.newReview(room, "%PDF-1.7 这不是真的 PDF".toByteArray(), name = "broken.pdf")
        ctx.jobs.drain()
        assertEquals(PreviewStatus.Failed, aqi.version(room, broken).previewStatus)
    }

    private val bare by lazy { testContext(clock = clock) }

    @Test fun `没配转换服务：Word 预览失败并说明原因`() = serverTest(bare) { client ->
        val (aqi, _, room) = Api(client).pair()
        val (_, v) = aqi.newReview(room, TestPdf.docx("x"), name = "a.docx")
        bare.jobs.drain()
        assertEquals("服务器还没有配置文档转换，暂时只能预览 PDF", aqi.version(room, v).previewError)
    }

    @Test fun `批注：按段落、区域、幻灯片钉位置；校验；正文只有作者能改；两人都能接受；只能删自己的；讨论`() = serverTest(ctx) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val (doc, versionId) = aqi.newReview(room, v1)
        // 预览没好时不能批注
        aqi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Slide)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        ctx.jobs.drain()
        val block = aqi.pages(room, doc.id, 1)[0].blocks[1]

        val r = chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Paragraph, block.rect, block.id, block.text), "单价和合同不一致", AnnotationKind.Proposal)
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val ann = r.body<Annotation>()
        assertEquals(AnnotationStatus.Open, ann.status)
        assertEquals(block.text, ann.anchor.quote)

        // 校验：没有这一页、区域没圈、段落没选
        chi.annotate(room, doc.id, versionId, AnnotationAnchor(3, AnchorKind.Slide)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Region)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Paragraph)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Region, NormRect(0.9, 0.9, 0.5, 0.5))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Slide), body = " ").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        assertEquals(HttpStatusCode.Created, chi.annotate(room, doc.id, versionId, AnnotationAnchor(2, AnchorKind.Region, NormRect(0.5, 0.5, 0.3, 0.2))).status)

        val path = "/api/v1/rooms/$room/reviews/${doc.id}/annotations/${ann.id}"
        aqi.patch(path, UpdateAnnotationRequest(body = Patch.of("改一下"))).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertEquals("单价按合同改成 1100", chi.patch(path, UpdateAnnotationRequest(body = Patch.of("单价按合同改成 1100"))).body<Annotation>().body)
        val accepted = aqi.patch(path, UpdateAnnotationRequest(status = Patch.of(AnnotationStatus.Accepted))).body<Annotation>()
        assertEquals(AnnotationStatus.Accepted, accepted.status)
        assertEquals(aqi.userId(), accepted.resolvedBy)
        assertNotNull(accepted.resolvedAt)
        val reopened = chi.patch(path, UpdateAnnotationRequest(status = Patch.of(AnnotationStatus.Open))).body<Annotation>()
        assertNull(reopened.resolvedBy)

        val reply = aqi.post("/api/v1/rooms/$room/annotations/${ann.id}/replies", CreateAnnotationReplyRequest(UuidV7.generate(), "合同那页我拍给你"))
        assertEquals(HttpStatusCode.Created, reply.status, reply.bodyAsText())
        assertEquals(ann.id, reply.body<AnnotationReply>().annotationId)

        aqi.delete(path).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertNotNull(chi.delete(path).body<Annotation>().deletedAt)
        aqi.post("/api/v1/rooms/$room/annotations/${ann.id}/replies", CreateAnnotationReplyRequest(UuidV7.generate(), "还在吗"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val boot = aqi.bootstrap(room)
        assertEquals(2, boot.annotations.size)
        assertEquals(1, boot.annotationReplies.size)
        assertEquals(1, boot.reviewDocuments.size)
    }

    @Test fun `新版本：没处理完的批注带过去并重新找到位置，找不到的标出来；已接受的不带；版本差异带页码`() = serverTest(ctx) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val (doc, first) = aqi.newReview(room, v1)
        ctx.jobs.drain()
        val blocks = aqi.pages(room, doc.id, 1)[0].blocks
        suspend fun on(block: Int, body: String) = chi.annotate(room, doc.id, first, AnnotationAnchor(1, AnchorKind.Paragraph, blocks[block].rect, blocks[block].id, blocks[block].text), body).body<Annotation>()
        val price = on(1, "单价不对")
        val payment = on(2, "首付太高")
        val title = on(0, "标题写全")
        chi.patch("/api/v1/rooms/$room/reviews/${doc.id}/annotations/${title.id}", UpdateAnnotationRequest(status = Patch.of(AnnotationStatus.Accepted)))
        chi.post("/api/v1/rooms/$room/annotations/${price.id}/replies", CreateAnnotationReplyRequest(UuidV7.generate(), "我去问"))

        // v2：单价那段挪到第 2 页，首付那段删掉了
        val v2 = aqi.newVersion(room, doc.id, TestPdf.pages(
            listOf("Quote for the kitchen renovation, 2026"),
            listOf("Delivery within six weeks after signing", "Unit price for cabinets is 1200 per meter"),
        ))
        assertEquals(2, v2.version)
        ctx.jobs.drain()
        val boot = aqi.bootstrap(room)
        assertEquals(2, boot.reviewDocuments.single().latestVersion)
        val carried = boot.annotations.filter { it.versionId == v2.id }.associateBy { it.carriedFromId }
        assertEquals(setOf(price.id, payment.id), carried.keys, "已接受的不带过去")

        val movedPrice = carried[price.id]!!
        assertEquals(2, movedPrice.anchor.page)
        assertEquals("p2-b2", movedPrice.anchor.ref)
        assertEquals(false, movedPrice.anchorLost)
        assertEquals(price.authorId, movedPrice.authorId)
        assertEquals("单价不对", movedPrice.body)
        assertTrue(carried[payment.id]!!.anchorLost)
        assertNull(carried[payment.id]!!.anchor.ref)

        val diff = aqi.get("/api/v1/rooms/$room/reviews/${doc.id}/diff?from=1&to=2").body<ReviewDiff>()
        val removed = diff.lines.filter { it.kind == DiffKind.Removed }.map { it.text to it.oldPage }
        val added = diff.lines.filter { it.kind == DiffKind.Added }.map { it.text to it.newPage }
        assertTrue("Payment: 30 percent upfront" to 1 in removed, removed.toString())
        assertTrue("Quote for the kitchen renovation, 2026" to 1 in added, added.toString())
        aqi.get("/api/v1/rooms/$room/reviews/${doc.id}/diff?from=1&to=1").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.get("/api/v1/rooms/$room/reviews/${doc.id}/diff?from=1&to=9").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `回收站：审稿文件连同批注一起；彻底删除后文件和预览图也没了；批注只有作者能恢复`() = serverTest(ctx) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val (doc, versionId) = aqi.newReview(room, v1)
        ctx.jobs.drain()
        val pageImage = aqi.pages(room, doc.id, 1)[0].imageFileId
        val original = aqi.version(room, versionId).fileId
        val own = chi.annotate(room, doc.id, versionId, AnnotationAnchor(1, AnchorKind.Slide)).body<Annotation>()
        val other = chi.annotate(room, doc.id, versionId, AnnotationAnchor(2, AnchorKind.Slide)).body<Annotation>()
        chi.delete("/api/v1/rooms/$room/reviews/${doc.id}/annotations/${other.id}")

        var trash = aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>()
        assertEquals(listOf(TrashType.Annotation), trash.items.map { it.type })
        aqi.post("/api/v1/rooms/$room/trash/annotation/${other.id}/restore").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)

        aqi.delete("/api/v1/rooms/$room/reviews/${doc.id}")
        trash = aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>()
        assertEquals(listOf(TrashType.ReviewDocument), trash.items.map { it.type }, "审稿文件在回收站里时，它的批注不单独列出")
        aqi.get("/api/v1/rooms/$room/reviews/${doc.id}/versions/1/pages").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/review_document/${doc.id}").status)
        aqi.get("/api/v1/files/$pageImage").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.get("/api/v1/files/$original").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        val boot = aqi.bootstrap(room)
        assertTrue(boot.reviewDocuments.isEmpty() && boot.reviewVersions.isEmpty() && boot.annotations.isEmpty())
        assertTrue(own.id !in boot.annotations.map { it.id })
    }
}
