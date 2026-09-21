package app.qichi.server.messages

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.MessageSearchPage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun Session.send(roomId: UUID, body: String, replyTo: UUID? = null, id: UUID = UuidV7.generate()): Message {
        val response = post("/api/v1/rooms/$roomId/messages", SendMessageRequest(id, "text", body, replyToId = replyTo))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun Session.history(roomId: UUID, query: String = ""): MessagePage =
        get("/api/v1/rooms/$roomId/messages$query").body()

    private suspend fun Session.search(roomId: UUID, q: String, extra: String = ""): MessageSearchPage {
        val response = get("/api/v1/rooms/$roomId/messages/search?q=${URLEncoder.encode(q, Charsets.UTF_8)}$extra")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private fun png(): ByteArray {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `发文字消息：createdSeq 等于 seq；同 id 重发返回已有的；别人用同 id 409`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val id = UuidV7.generate()
        val sent = aqi.send(roomId, "  晚上吃什么？ ", id = id)
        assertEquals("晚上吃什么？", sent.body, "首尾空白去掉")
        assertEquals(MessageKind.Text, sent.kind)
        assertEquals(sent.seq, sent.createdSeq)
        assertEquals(aqi.userId(), sent.authorId)

        val again = aqi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(id, "text", "晚上吃什么？"))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(sent, again.body<Message>())
        xiaochi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(id, "text", "x"))
            .assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)
    }

    @Test
    fun `参数不对：空消息、超长、文字带文件、未知种类、图片缺文件`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val path = "/api/v1/rooms/$roomId/messages"
        suspend fun bad(req: SendMessageRequest) = aqi.post(path, req).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        bad(SendMessageRequest(UuidV7.generate(), "text", "   "))
        bad(SendMessageRequest(UuidV7.generate(), "text", "字".repeat(10_001)))
        bad(SendMessageRequest(UuidV7.generate(), "text", "hi", fileId = UuidV7.generate()))
        bad(SendMessageRequest(UuidV7.generate(), "system", "hi"))
        bad(SendMessageRequest(UuidV7.generate(), "image"))
        bad(SendMessageRequest(UuidV7.generate(), "image", fileId = UuidV7.generate()))
        bad(SendMessageRequest(UuidV7.generate(), "text", "hi", replyToId = UuidV7.generate()))
        assertEquals(HttpStatusCode.Created, aqi.post(path, SendMessageRequest(UuidV7.generate(), "text", "字".repeat(10_000))).status)
    }

    @Test
    fun `图片与文件消息带出附件；种类不符或别的房间的文件 400`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, roomId) = api.pair()
        val image = aqi.upload(roomId, png()).body<FileMeta>()
        val doc = aqi.upload(roomId, "hello".toByteArray(), fileName = "a.txt", kind = "file", contentType = "text/plain").body<FileMeta>()
        val path = "/api/v1/rooms/$roomId/messages"

        val sent = aqi.post(path, SendMessageRequest(UuidV7.generate(), "image", fileId = image.id))
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        val message = sent.body<Message>()
        assertEquals(image, message.file)
        assertEquals("", message.body)

        assertEquals(image, aqi.post(path, SendMessageRequest(UuidV7.generate(), "file", fileId = image.id)).body<Message>().file)
        assertEquals(doc, aqi.post(path, SendMessageRequest(UuidV7.generate(), "file", "看看这个", fileId = doc.id)).body<Message>().file)
        aqi.post(path, SendMessageRequest(UuidV7.generate(), "image", fileId = doc.id))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val otherRoom = aqi.createRoom("另一个").room.id
        val elsewhere = aqi.upload(otherRoom, png()).body<FileMeta>()
        aqi.post(path, SendMessageRequest(UuidV7.generate(), "image", fileId = elsewhere.id))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `回复：服务端填写原作者和前 60 字摘要`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val original = aqi.send(roomId, "周末".repeat(40))
        val reply = xiaochi.send(roomId, "好呀", replyTo = original.id)
        assertEquals(original.id, reply.replyToId)
        assertEquals(aqi.userId(), reply.replyAuthorId)
        assertEquals("周末".repeat(30), reply.replyExcerpt)

        val image = aqi.upload(roomId, png()).body<FileMeta>()
        val photo = aqi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "image", fileId = image.id)).body<Message>()
        assertEquals("[图片]", xiaochi.send(roomId, "好看", replyTo = photo.id).replyExcerpt)
    }

    @Test
    fun `翻历史：按 createdSeq 降序分页`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val sent = (1..5).map { (if (it % 2 == 0) xiaochi else aqi).send(roomId, "第 $it 条") }

        val first = aqi.history(roomId, "?limit=2")
        assertEquals(listOf("第 5 条", "第 4 条"), first.messages.map { it.body })
        assertTrue(first.hasMore)
        val second = aqi.history(roomId, "?limit=2&beforeSeq=${first.messages.last().createdSeq}")
        assertEquals(listOf("第 3 条", "第 2 条"), second.messages.map { it.body })
        val last = aqi.history(roomId, "?limit=2&beforeSeq=${second.messages.last().createdSeq}")
        assertEquals(listOf("第 1 条"), last.messages.map { it.body })
        assertFalse(last.hasMore)
        assertEquals(5, aqi.history(roomId).messages.size)
        assertEquals(sent.first().id, aqi.history(roomId).messages.last().id)

        aqi.get("/api/v1/rooms/$roomId/messages?limit=0").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.get("/api/v1/rooms/$roomId/messages?beforeSeq=abc").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `撤回：仅作者；清空正文和回复摘要，保留谁在何时撤回；搜索不到原文`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val secret = aqi.send(roomId, "发错了的暗号 moonlight")
        val reply = xiaochi.send(roomId, "什么？", replyTo = secret.id)
        assertEquals(1, aqi.search(roomId, "暗号").messages.size)

        xiaochi.post("/api/v1/rooms/$roomId/messages/${secret.id}/retract")
            .assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)

        val response = aqi.post("/api/v1/rooms/$roomId/messages/${secret.id}/retract")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val retracted = response.body<Message>()
        assertEquals("", retracted.body)
        assertEquals(aqi.userId(), retracted.retractedBy)
        assertNotNull(retracted.retractedAt)
        assertEquals(secret.createdSeq, retracted.createdSeq, "位置不变")
        assertTrue(retracted.seq > secret.seq)

        val history = xiaochi.history(roomId).messages.associateBy { it.id }
        assertEquals(aqi.userId(), history.getValue(secret.id).retractedBy, "对方仍能看到谁撤回了")
        assertNull(history.getValue(reply.id).replyExcerpt, "回复里的摘要也清空")
        assertEquals(secret.id, history.getValue(reply.id).replyToId)

        assertTrue(aqi.search(roomId, "暗号").messages.isEmpty())
        assertTrue(aqi.search(roomId, "moonlight").messages.isEmpty())

        // 重复撤回直接返回
        assertEquals(retracted, aqi.post("/api/v1/rooms/$roomId/messages/${secret.id}/retract").body<Message>())

        // 两条消息的变化都能同步到
        val sync = xiaochi.get("/api/v1/rooms/$roomId/sync?since=${reply.seq}").body<SyncResponse>()
        val changed = sync.changes.filter { it.type == EntityType.Message }.map { it.id }.toSet()
        assertEquals(setOf(secret.id, reply.id), changed)
        val synced = sync.changes.first { it.id == reply.id }.data!!
        assertNull(QichiJson.decodeFromJsonElement(Message.serializer(), synced).replyExcerpt)
    }

    @Test
    fun `撤回图片：附件从服务器删除`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val image = aqi.upload(roomId, png()).body<FileMeta>()
        assertEquals(HttpStatusCode.OK, aqi.get("/api/v1/files/${image.id}/thumb?w=200").status)
        val message = aqi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "image", fileId = image.id)).body<Message>()

        val retracted = aqi.post("/api/v1/rooms/$roomId/messages/${message.id}/retract").body<Message>()
        assertNull(retracted.file)
        aqi.get("/api/v1/files/${image.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `删除进回收站：任一成员都能删，搜索不再出现，重复删除直接返回`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val message = aqi.send(roomId, "河边的风")
        val response = xiaochi.delete("/api/v1/rooms/$roomId/messages/${message.id}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val deleted = response.body<Message>()
        assertEquals(xiaochi.userId(), deleted.deletedBy)
        assertEquals("河边的风", deleted.body, "删除保留内容，恢复时原样回来")
        assertTrue(aqi.search(roomId, "河边").messages.isEmpty())
        assertEquals(deleted, aqi.delete("/api/v1/rooms/$roomId/messages/${message.id}").body<Message>())
        aqi.delete("/api/v1/rooms/$roomId/messages/${UuidV7.generate()}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `搜索：不分大小写，通配符按字面匹配，游标分页`() = serverTest { client ->
        val api = Api(client)
        val (aqi, xiaochi, roomId) = api.pair()
        aqi.send(roomId, "Hello 河边")
        aqi.send(roomId, "100% 同意")
        aqi.send(roomId, "100 同意")
        repeat(5) { xiaochi.send(roomId, "河边散步 $it") }

        assertEquals(listOf("Hello 河边"), aqi.search(roomId, "hello").messages.map { it.body })
        assertEquals(listOf("100% 同意"), aqi.search(roomId, "0%").messages.map { it.body })

        val page1 = aqi.search(roomId, "河边", "&limit=4")
        assertEquals(4, page1.messages.size)
        assertEquals("河边散步 4", page1.messages.first().body)
        val page2 = aqi.search(roomId, "河边", "&limit=4&cursor=${page1.nextCursor}")
        assertEquals(listOf("河边散步 0", "Hello 河边"), page2.messages.map { it.body })
        assertNull(page2.nextCursor)

        aqi.get("/api/v1/rooms/$roomId/messages/search?q=%20").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.get("/api/v1/rooms/$roomId/messages/search?q=a&cursor=xyz").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val outsider = api.outsider(aqi)
        outsider.get("/api/v1/rooms/$roomId/messages/search?q=a").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.get("/api/v1/rooms/$roomId/messages").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", "hi"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }
}
