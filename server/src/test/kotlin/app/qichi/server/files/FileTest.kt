package app.qichi.server.files

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest

import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(0x9A, 0x55, 0x52)
        g.fillRect(0, 0, width / 2, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun ByteArray.image(): BufferedImage = ImageIO.read(inputStream())

    @Test
    fun `上传图片：记录类型、宽高、sha256；同 id 重传返回已有的`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val bytes = png(1000, 500)
        val id = UuidV7.generate()

        val created = aqi.upload(roomId, bytes, id = id, contentType = "application/octet-stream")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val meta = created.body<FileMeta>()
        assertEquals(id, meta.id)
        assertEquals(FileKind.Image, meta.kind)
        assertEquals("image/png", meta.mimeType, "图片类型按文件头识别，不信任客户端声明")
        assertEquals(1000, meta.width)
        assertEquals(500, meta.height)
        assertEquals(bytes.size.toLong(), meta.sizeBytes)
        assertEquals(sha256(bytes), meta.sha256)
        assertEquals("photo.png", meta.fileName)

        val again = aqi.upload(roomId, bytes, id = id)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(meta, again.body<FileMeta>())
    }

    @Test
    fun `下载：成员拿到原文件，支持 Range 与 ETag，非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, xiaochi, roomId) = api.pair()
        val bytes = png(300, 200)
        val meta = aqi.upload(roomId, bytes).body<FileMeta>()

        val full = xiaochi.get("/api/v1/files/${meta.id}")
        assertEquals(HttpStatusCode.OK, full.status)
        assertContentEquals(bytes, full.bodyAsBytes())
        val etag = full.headers[HttpHeaders.ETag]!!
        assertEquals("\"${meta.sha256}\"", etag)
        assertContains(full.headers[HttpHeaders.ContentDisposition]!!, "inline")

        val part = xiaochi.get("/api/v1/files/${meta.id}") { header(HttpHeaders.Range, "bytes=0-9") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertContentEquals(bytes.copyOfRange(0, 10), part.bodyAsBytes())

        val cached = xiaochi.get("/api/v1/files/${meta.id}") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, cached.status)

        val stranger = api.outsider(aqi)
        stranger.get("/api/v1/files/${meta.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        stranger.get("/api/v1/files/${meta.id}/thumb?w=200").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        stranger.upload(roomId, bytes).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.get("/api/v1/files/${UuidV7.generate()}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `图片超过 20MB 返回 413；kind 排在文件后面也一样`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val big = png(10, 10).copyOf((Limits.IMAGE_MAX_BYTES + 1).toInt())
        aqi.upload(roomId, big).assertProblem(HttpStatusCode.PayloadTooLarge, ProblemCode.PayloadTooLarge)
        aqi.upload(roomId, big, kindFirst = false).assertProblem(HttpStatusCode.PayloadTooLarge, ProblemCode.PayloadTooLarge)
        // 同样大小作为普通文件可以
        assertEquals(HttpStatusCode.Created, aqi.upload(roomId, big, kind = "file", fileName = "big.bin").status)
    }

    @Test
    fun `不是图片却说是图片返回 415；普通文件按声明的类型记录`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val pdf = "%PDF-1.7 行程草稿".toByteArray()
        aqi.upload(roomId, pdf, fileName = "a.pdf", contentType = "application/pdf")
            .assertProblem(HttpStatusCode.UnsupportedMediaType, ProblemCode.UnsupportedMediaType)

        val response = aqi.upload(roomId, pdf, fileName = "trip.pdf", kind = "file", contentType = "application/pdf")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val meta = response.body<FileMeta>()
        assertEquals("application/pdf", meta.mimeType)
        assertNull(meta.width)

        val download = aqi.get("/api/v1/files/${meta.id}")
        assertContains(download.headers[HttpHeaders.ContentDisposition]!!, "attachment")
        assertContains(download.headers[HttpHeaders.ContentDisposition]!!, "trip.pdf")
        assertContentEquals(pdf, download.bodyAsBytes())
    }

    @Test
    fun `参数不对：缺文件、未知种类、非法 id；别人用过的 id 返回 409`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val bytes = png(20, 20)
        aqi.upload(roomId, bytes, kind = "epub").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.upload(roomId, bytes, kind = "banana").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val id = UuidV7.generate()
        assertEquals(HttpStatusCode.Created, aqi.upload(roomId, bytes, id = id).status)
        xiaochi.upload(roomId, bytes, id = id).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)
    }

    @Test
    fun `缩略图：按宽度生成 JPEG，不放大；普通文件和非法宽度 404 或 400`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val meta = aqi.upload(roomId, png(1000, 500)).body<FileMeta>()

        val thumb = aqi.get("/api/v1/files/${meta.id}/thumb?w=200")
        assertEquals(HttpStatusCode.OK, thumb.status, thumb.bodyAsText())
        assertEquals("image/jpeg", thumb.headers[HttpHeaders.ContentType])
        val image = thumb.bodyAsBytes().image()
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        // 第二次直接读缓存
        assertEquals(200, aqi.get("/api/v1/files/${meta.id}/thumb?w=200").bodyAsBytes().image().width)
        assertEquals(800, aqi.get("/api/v1/files/${meta.id}/thumb?w=800").bodyAsBytes().image().width)

        val small = aqi.upload(roomId, png(120, 60)).body<FileMeta>()
        assertEquals(120, aqi.get("/api/v1/files/${small.id}/thumb?w=400").bodyAsBytes().image().width)

        aqi.get("/api/v1/files/${meta.id}/thumb?w=300").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val doc = aqi.upload(roomId, "hello".toByteArray(), fileName = "a.txt", kind = "file", contentType = "text/plain").body<FileMeta>()
        aqi.get("/api/v1/files/${doc.id}/thumb?w=200").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `WebP 能识别并出缩略图；HEIC 只读尺寸，缩略图 404`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        val w = aqi.upload(roomId, webp, fileName = "a.webp", contentType = "image/webp")
        assertEquals(HttpStatusCode.Created, w.status, w.bodyAsText())
        val wm = w.body<FileMeta>()
        assertEquals("image/webp", wm.mimeType)
        assertEquals(1, wm.width)
        assertEquals(1, aqi.get("/api/v1/files/${wm.id}/thumb?w=200").bodyAsBytes().image().width)

        val heic = heicStub(width = 4032, height = 3024)
        val h = aqi.upload(roomId, heic, fileName = "a.heic", contentType = "image/heic")
        assertEquals(HttpStatusCode.Created, h.status, h.bodyAsText())
        val hm = h.body<FileMeta>()
        assertEquals("image/heic", hm.mimeType)
        assertEquals(4032, hm.width)
        assertEquals(3024, hm.height)
        aqi.get("/api/v1/files/${hm.id}/thumb?w=200").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `中文文件名原样保存，下载时用 filename* 给出`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val meta = aqi.upload(roomId, "x".toByteArray(), fileName = "行程草稿.pdf", kind = "file", contentType = "application/pdf").body<FileMeta>()
        assertEquals("行程草稿.pdf", meta.fileName)
        val disposition = aqi.get("/api/v1/files/${meta.id}").headers[HttpHeaders.ContentDisposition]!!
        assertTrue(disposition.contains("filename*=utf-8''%E8%A1%8C", ignoreCase = true), disposition)
    }

    /** 只有文件头与 ispe 盒子的「HEIC」：够服务端识别和读尺寸（一个小块 512×512，整图更大）。 */
    private fun heicStub(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        int(24); out.write("ftypheic".toByteArray()); int(0); out.write("mif1heic".toByteArray())
        for ((w, h) in listOf(512 to 512, width to height)) {
            int(20); out.write("ispe".toByteArray()); int(0); int(w); int(h)
        }
        return out.toByteArray()
    }
}
