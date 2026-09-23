package app.qichi.server.reading

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Book
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Patch
import app.qichi.shared.api.PutReadingProgressRequest
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateBookRequest
import app.qichi.shared.api.UpdateHighlightRequest
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private fun epub(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container/>".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    private suspend fun Session.addBook(room: UUID, title: String = "海边的旅店"): Book {
        val file = upload(room, epub(), fileName = "book.epub", kind = "epub", contentType = "application/epub+zip").body<FileMeta>()
        return post("/api/v1/rooms/$room/books", CreateBookRequest(UuidV7.generate(), file.id, "  $title ", "某某")).body<Book>()
    }

    @Test fun `上传 EPUB 放上书架：对方同步得到；不是 EPUB 的文件被拒绝；共读计划可以改`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val book = aqi.addBook(room)
        assertEquals("海边的旅店", book.title)
        assertTrue(book.sizeBytes > 0)
        assertEquals(listOf(book.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().books.map { it.id })

        aqi.upload(room, "not a zip".toByteArray(), fileName = "fake.epub", kind = "epub", contentType = "application/epub+zip")
            .assertProblem(HttpStatusCode.UnsupportedMediaType, ProblemCode.UnsupportedMediaType)
        val image = aqi.upload(room, byteArrayOf(1, 2, 3), fileName = "x.bin", kind = "file", contentType = "application/octet-stream").body<FileMeta>()
        aqi.post("/api/v1/rooms/$room/books", CreateBookRequest(UuidV7.generate(), image.id, "x")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val planned = chi.patch("/api/v1/rooms/$room/books/${book.id}", UpdateBookRequest(planTargetDate = Patch.of(LocalDate.of(2026, 10, 31)), planNote = Patch.of("每周读两章"))).body<Book>()
        assertEquals(LocalDate.of(2026, 10, 31), planned.planTargetDate)
        assertEquals("每周读两章", planned.planNote)
    }

    @Test fun `进度各自一条：再次保存沿用已有的 id；另一个人有自己的一条`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val book = aqi.addBook(room)
        val path = "/api/v1/rooms/$room/books/${book.id}/progress"
        val first = aqi.put(path, PutReadingProgressRequest(UuidV7.generate(), """{"href":"ch1"}""", 0.1)).body<ReadingProgress>()
        val again = aqi.put(path, PutReadingProgressRequest(UuidV7.generate(), """{"href":"ch2"}""", 0.3)).body<ReadingProgress>()
        assertEquals(first.id, again.id)
        assertEquals(0.3, again.progress)
        val hers = chi.put(path, PutReadingProgressRequest(UuidV7.generate(), """{"href":"ch1"}""", 0.05)).body<ReadingProgress>()
        assertTrue(hers.id != first.id)
        assertEquals(2, aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().readingProgress.size)
        aqi.put(path, PutReadingProgressRequest(UuidV7.generate(), "x", 1.5)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `标记：自己的都同步，对方的只有共享了才看得到；改回私有时对方那边收到删除；只能改自己的`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val book = aqi.addBook(room)
        val path = "/api/v1/rooms/$room/books/${book.id}/highlights"
        val private = aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Highlight, "{}", "不必急着去哪里", "私下想的")).body<Highlight>()
        val shared = aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Excerpt, "{}", "先在这里坐一会儿", "我也喜欢这一句", shared = true)).body<Highlight>()
        aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Highlight, "{}", "")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        assertEquals(setOf(private.id, shared.id), aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.map { it.id }.toSet())
        assertEquals(listOf(shared.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.map { it.id })
        val chiChanges = chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.filter { it.type == EntityType.Highlight }
        assertEquals(ChangeOp.Delete, chiChanges.single { it.id == private.id }.op, "对方看不到私有的内容")

        chi.patch("$path/${shared.id}", UpdateHighlightRequest(note = Patch.of("x"))).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        chi.patch("$path/${private.id}", UpdateHighlightRequest(note = Patch.of("x"))).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val before = chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().toSeq
        aqi.patch("$path/${shared.id}", UpdateHighlightRequest(shared = Patch.of(false)))
        val after = chi.get("/api/v1/rooms/$room/sync?since=$before").body<SyncResponse>().changes.single { it.id == shared.id }
        assertEquals(ChangeOp.Delete, after.op)

        aqi.delete("$path/${private.id}")
        assertTrue(aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.single { it.id == private.id }.deletedAt != null)
    }

    @Test fun `拿下书架进回收站；彻底删除时进度、标记和文件一起删；非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val book = aqi.addBook(room)
        aqi.put("/api/v1/rooms/$room/books/${book.id}/progress", PutReadingProgressRequest(UuidV7.generate(), "{}", 0.2))
        val h = chi.post("/api/v1/rooms/$room/books/${book.id}/highlights", CreateHighlightRequest(UuidV7.generate(), HighlightKind.Bookmark, "{}")).body<Highlight>()
        api.outsider(aqi).get("/api/v1/rooms/$room/books").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        chi.delete("/api/v1/rooms/$room/books/${book.id}")
        assertTrue(aqi.get("/api/v1/rooms/$room/books").body<List<Book>>().isEmpty())
        assertEquals(listOf(TrashType.Book), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/book/${book.id}").status)
        val changes = chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
        assertEquals(ChangeOp.Delete, changes.last { it.id == h.id }.op)
        assertEquals(ChangeOp.Delete, changes.last { it.type == EntityType.ReadingProgress }.op)
        aqi.get("/api/v1/files/${book.fileId}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }
}
