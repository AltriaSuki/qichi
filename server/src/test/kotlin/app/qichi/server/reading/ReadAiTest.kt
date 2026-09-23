package app.qichi.server.reading

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.ai.FakeGateway
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiReadExplainRequest
import app.qichi.shared.api.Book
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadAiTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway().apply { answer = "这句是说，不必赶路，先让自己停一停。" }
    private val clock = MutableClock()

    private fun epub(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry() }
    }.toByteArray()

    private suspend fun Session.addBook(room: UUID): Book {
        val file = upload(room, epub(), fileName = "b.epub", kind = "epub", contentType = "application/epub+zip").body<FileMeta>()
        return post("/api/v1/rooms/$room/books", CreateBookRequest(UuidV7.generate(), file.id, "海边的旅店", "林晚")).body()
    }

    @Test fun `解释：202 后写成一条只有自己看得到的 AI 标记；提示词里有书名、选中的文字和上下文`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val book = aqi.addBook(room)
            val jobId = UuidV7.generate()
            val response = aqi.post("/api/v1/rooms/$room/ai/read-explain",
                AiReadExplainRequest(jobId, book.id, ReadExplainMode.Explain, " 不必急着去哪里 ", "{}", before = "雨是从傍晚开始下的。", after = "她于是真的坐了很久。"))
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(AiJobAccepted(jobId, AiJobStatus.Queued), response.body<AiJobAccepted>())
            ctx.jobs.drain()

            val prompt = gateway.requests.single().messages.single().content
            assertTrue(prompt.contains("《海边的旅店》（林晚）"))
            assertTrue(prompt.contains("不必急着去哪里") && prompt.contains("雨是从傍晚开始下的。"))
            val note = aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.single()
            assertEquals(jobId, note.id)
            assertEquals(HighlightKind.Ai, note.kind)
            assertEquals("这句是说，不必赶路，先让自己停一停。", note.note)
            assertFalse(note.shared)
            assertTrue(chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.isEmpty(), "对方看不到")
            assertEquals("highlight:$jobId", aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().resultRef)
        }
    }

    @Test fun `对比：带上自己的和对方共享的标注，不带对方私有的；客户端不能直接建 AI 标记`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val book = aqi.addBook(room)
            val path = "/api/v1/rooms/$room/books/${book.id}/highlights"
            aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Highlight, "{}", "楼下的灯一直亮着", "像有人等"))
            chi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Excerpt, "{}", "先在这里坐一会儿", "我也喜欢", shared = true))
            chi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Excerpt, "{}", "私下的一句", null, shared = false))
            aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Ai, "{}", "x")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

            aqi.post("/api/v1/rooms/$room/ai/read-explain", AiReadExplainRequest(UuidV7.generate(), book.id, ReadExplainMode.Compare, "她把信重新展开", "{}"))
            ctx.jobs.drain()
            val prompt = gateway.requests.single().messages.single().content
            assertTrue(prompt.contains("aqi：楼下的灯一直亮着 —— 像有人等"), prompt)
            assertTrue(prompt.contains("xiaochi：先在这里坐一会儿 —— 我也喜欢"))
            assertFalse(prompt.contains("私下的一句"))
        }
    }

    @Test fun `没配置 AI 返回 503；别的房间的书 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        val book = aqi.addBook(room)
        aqi.post("/api/v1/rooms/$room/ai/read-explain", AiReadExplainRequest(UuidV7.generate(), book.id, ReadExplainMode.Explain, "x", "{}"))
            .assertProblem(HttpStatusCode.ServiceUnavailable, ProblemCode.AiUnavailable)
    }
}
