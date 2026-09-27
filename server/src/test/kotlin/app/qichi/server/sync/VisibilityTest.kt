package app.qichi.server.sync

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.shared.api.Book
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QnaToday
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.UpdateHighlightRequest
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsBytes
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可见性规则集中在 Visibility 之后（P13-12）：快照、同步、导出、问答今天看到的必须一致。 */
class VisibilityTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private fun epub(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container/>".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    private suspend fun Session.addBook(room: UUID): Book {
        val file = upload(room, epub(), fileName = "book.epub", kind = "epub", contentType = "application/epub+zip").body<FileMeta>()
        return post("/api/v1/rooms/$room/books", CreateBookRequest(UuidV7.generate(), file.id, "海边的旅店", "某某")).body<Book>()
    }

    private suspend fun Session.exportedData(room: UUID): String {
        ZipInputStream(get("/api/v1/rooms/$room/export").bodyAsBytes().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "data.json") return zip.readBytes().decodeToString()
            }
        }
        error("导出里没有 data.json")
    }

    /** [viewer] 从快照、同步（看得到的 upsert）、导出三处看到的 [ids] 里的实体 */
    private suspend fun seen(viewer: Session, room: UUID, ids: Set<UUID>): Map<String, Set<UUID>> {
        val boot = viewer.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>()
        val sync = viewer.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
            .filter { it.op == ChangeOp.Upsert }.map { it.id }
        val export = viewer.exportedData(room)
        return mapOf(
            "快照" to (boot.answers.map { it.id } + boot.highlights.map { it.id }).filter { it in ids }.toSet(),
            "同步" to sync.filter { it in ids }.toSet(),
            "导出" to ids.filter { export.contains(it.toString()) }.toSet(),
        )
    }

    @Test
    fun `对方没揭晓的回答、没共享的标记：快照、同步、导出、今天的问答里都看不到；揭晓、共享之后都看得到`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room"
        val round = aqi.get("$path/qna/today").body<QnaToday>().round.id
        val answer = UuidV7.generate()
        aqi.put("$path/qna/rounds/$round/answer", WriteAnswerRequest(answer, "我的秘密"))
        val book = aqi.addBook(room)
        val highlight = aqi.post(
            "$path/books/${book.id}/highlights",
            CreateHighlightRequest(UuidV7.generate(), HighlightKind.Highlight, "{}", "不必急着去哪里", "私下想的"),
        ).body<Highlight>()
        val ids = setOf(answer, highlight.id)

        seen(aqi, room, ids).forEach { (where, got) -> assertEquals(ids, got, "自己的在「$where」里都看得到") }
        seen(chi, room, ids).forEach { (where, got) -> assertTrue(got.isEmpty(), "对方的在「$where」里看不到：$got") }
        assertNull(chi.get("$path/qna/today").body<QnaToday>().partnerAnswer)
        assertFalse(chi.exportedData(room).contains("我的秘密"))

        aqi.post("$path/qna/rounds/$round/confirm")
        chi.put("$path/qna/rounds/$round/answer", WriteAnswerRequest(UuidV7.generate(), "我也有秘密"))
        chi.post("$path/qna/rounds/$round/confirm")
        aqi.patch("$path/books/${book.id}/highlights/${highlight.id}", UpdateHighlightRequest(shared = Patch.of(true)))

        seen(chi, room, ids).forEach { (where, got) -> assertEquals(ids, got, "揭晓、共享之后在「$where」里看得到") }
        assertEquals("我的秘密", chi.get("$path/qna/today").body<QnaToday>().partnerAnswer?.body)
    }
}
