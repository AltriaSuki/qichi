package app.qichi.server.export

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Message
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.call.body
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import app.qichi.shared.rules.DocumentImages
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                put(e.name, zip.readBytes().decodeToString())
            }
        }
    }

    @Test fun `导出 ZIP：data json、聊天记录、文稿最新版；不含撤回和回收站里的内容；可选带上附件`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val base = "/api/v1/rooms/$room"
        chi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "text", "周六去海边吧"))
        val secret = aqi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "text", "发错了的一句")).body<Message>()
        aqi.post("$base/messages/${secret.id}/retract")
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val file = aqi.upload(room, png, fileName = "sea.png").body<FileMeta>()
        aqi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "image", "海边", fileId = file.id))
        aqi.post("$base/ideas", CreateIdeaRequest(UuidV7.generate(), "阳台种柠檬树"))
        val gone = aqi.post("$base/ideas", CreateIdeaRequest(UuidV7.generate(), "删掉的灵感")).body<Idea>()
        aqi.delete("$base/ideas/${gone.id}")
        val doc = aqi.post("$base/documents", CreateDocumentRequest(UuidV7.generate(), "给明年的信")).body<Document>()
        aqi.post("$base/documents/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, "第一稿"))
        aqi.post("$base/documents/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, "## 我们\n第二稿"))

        val response = chi.get("$base/export")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers[HttpHeaders.ContentDisposition]!!, "attachment")
        val files = unzip(response.bodyAsBytes())
        assertEquals(setOf("说明.txt", "data.json", "聊天记录.md", "文稿/给明年的信.md"), files.keys)
        val chat = files["聊天记录.md"]!!
        assertContains(chat, "xiaochi**：周六去海边吧")
        assertContains(chat, "[图片 sea.png] 海边")
        assertFalse(chat.contains("发错了"), "撤回的内容不导出")
        val data = files["data.json"]!!
        assertContains(data, "阳台种柠檬树")
        assertFalse(data.contains("删掉的灵感"), "回收站里的不导出")
        assertFalse(data.contains("发错了"))
        assertEquals("## 我们\n第二稿", files["文稿/给明年的信.md"])

        val withFiles = unzip(chi.get("$base/export?files=true").bodyAsBytes())
        assertTrue(withFiles.keys.any { it.startsWith("附件/") && it.endsWith("sea.png") })

        api.outsider(aqi).get("$base/export").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `文稿里的照片：带附件导出时换成附件路径，不带时写「（照片）」；别的房间的文件不导出`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        val base = "/api/v1/rooms/$room"
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val photo = aqi.upload(room, png, fileName = "sunset.png").body<FileMeta>()
        val otherRoom = aqi.createRoom("另一个").room.id
        val foreign = aqi.upload(otherRoom, png, fileName = "other.png").body<FileMeta>()
        val doc = aqi.post("$base/documents", CreateDocumentRequest(UuidV7.generate(), "海边周末")).body<Document>()
        val body = "看日落\n\n${DocumentImages.markdown(photo.id, "日落")}\n\n${DocumentImages.markdown(foreign.id)}"
        aqi.post("$base/documents/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, body))

        assertEquals("看日落\n\n（日落）\n\n（照片）", unzip(aqi.get("$base/export").bodyAsBytes())["文稿/海边周末.md"])

        val withFiles = unzip(aqi.get("$base/export?files=true").bodyAsBytes())
        val photoName = withFiles.keys.single { it.startsWith("附件/") && it.endsWith("sunset.png") }.removePrefix("附件/")
        assertEquals("看日落\n\n![日落](../附件/$photoName)\n\n（照片）", withFiles["文稿/海边周末.md"])
        assertFalse(withFiles.keys.any { it.endsWith("other.png") }, "别的房间的文件不导出")
    }
}
