package app.qichi.server.trash

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.Change
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Event
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.Mood
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val clock = MutableClock()

    private suspend fun Session.message(roomId: UUID, body: String, replyTo: UUID? = null): Message =
        post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", body, replyToId = replyTo)).body()

    private suspend fun Session.todo(roomId: UUID, title: String, parent: UUID? = null): Todo =
        post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), title, parentId = parent)).body()

    private suspend fun Session.trash(roomId: UUID, query: String = ""): TrashPage {
        val response = get("/api/v1/rooms/$roomId/trash$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    /** 删除后隔一秒，列表顺序才确定 */
    private suspend fun Session.remove(path: String) {
        val response = delete(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        clock.advance(Duration.ofSeconds(1))
    }

    @Test
    fun `列表按删除时间倒序，四种类型都在，子任务不单独列出，可以分页`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val msg = aqi.message(roomId, "说错话了")
        val mood = aqi.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Tired, 6)).body<Mood>()
        val parent = aqi.todo(roomId, "搬家")
        aqi.todo(roomId, "打包书", parent = parent.id)
        val event = aqi.post(
            "/api/v1/rooms/$roomId/events",
            CreateEventRequest(UuidV7.generate(), "看房", allDay = true, startDate = LocalDate.now(), endDate = LocalDate.now()),
        ).body<Event>()

        xiaochi.remove("/api/v1/rooms/$roomId/messages/${msg.id}")
        aqi.remove("/api/v1/rooms/$roomId/moods/${mood.id}")
        aqi.remove("/api/v1/rooms/$roomId/todos/${parent.id}")
        xiaochi.remove("/api/v1/rooms/$roomId/events/${event.id}")

        val all = aqi.trash(roomId)
        assertEquals(listOf(TrashType.Event, TrashType.Todo, TrashType.Mood, TrashType.Message), all.items.map { it.type })
        assertEquals(listOf(event.id, parent.id, mood.id, msg.id), all.items.map { it.id })
        assertEquals(xiaochi.userId(), all.items.first().deletedBy)
        assertEquals("说错话了", QichiJson.decodeFromJsonElement(Message.serializer(), all.items.last().data).body)
        assertNull(all.nextCursor)

        val page1 = xiaochi.trash(roomId, "?limit=3")
        assertEquals(3, page1.items.size)
        val page2 = xiaochi.trash(roomId, "?limit=3&cursor=${page1.nextCursor}")
        assertEquals(listOf(msg.id), page2.items.map { it.id })
        assertNull(page2.nextCursor)

        aqi.get("/api/v1/rooms/$roomId/trash?cursor=abc").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `恢复：实体与删除前完全一致，消息回到原位置，子任务一起回来`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val before = aqi.message(roomId, "第一条")
        aqi.message(roomId, "第二条")
        val parent = aqi.todo(roomId, "搬家")
        val child = aqi.todo(roomId, "打包书", parent = parent.id)

        xiaochi.remove("/api/v1/rooms/$roomId/messages/${before.id}")
        aqi.remove("/api/v1/rooms/$roomId/todos/${parent.id}")

        val response = aqi.post("/api/v1/rooms/$roomId/trash/message/${before.id}/restore")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val change = response.body<Change>()
        assertEquals(ChangeOp.Upsert, change.op)
        val restored = QichiJson.decodeFromJsonElement(Message.serializer(), change.data!!)
        assertEquals(before.copy(seq = restored.seq, updatedAt = restored.updatedAt), restored)
        assertEquals(before.createdSeq, restored.createdSeq)
        val history = aqi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>()
        assertEquals(listOf("第二条", "第一条"), history.messages.map { it.body })

        aqi.post("/api/v1/rooms/$roomId/trash/todo/${child.id}/restore").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.post("/api/v1/rooms/$roomId/trash/todo/${parent.id}/restore").body<Change>()
        val sync = aqi.get("/api/v1/rooms/$roomId/sync?since=0").body<SyncResponse>()
        val todos = sync.changes.filter { it.type == EntityType.Todo }.associate { it.id to QichiJson.decodeFromJsonElement(Todo.serializer(), it.data!!) }
        assertNull(todos.getValue(parent.id).deletedAt)
        assertNull(todos.getValue(child.id).deletedAt)
        assertTrue(aqi.trash(roomId).items.isEmpty())

        // 不在回收站里的、不认识的类型：404
        aqi.post("/api/v1/rooms/$roomId/trash/message/${before.id}/restore").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.post("/api/v1/rooms/$roomId/trash/banana/${before.id}/restore").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `心情只有作者能恢复或彻底删除`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val mood = aqi.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Calm, 3)).body<Mood>()
        aqi.remove("/api/v1/rooms/$roomId/moods/${mood.id}")
        xiaochi.post("/api/v1/rooms/$roomId/trash/mood/${mood.id}/restore").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        xiaochi.delete("/api/v1/rooms/$roomId/trash/mood/${mood.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertEquals(HttpStatusCode.OK, aqi.post("/api/v1/rooms/$roomId/trash/mood/${mood.id}/restore").status)
    }

    @Test
    fun `彻底删除：产生 delete 变化，回复摘要清空，附件删除，子任务一起删`() = serverTest(testContext(clock = clock)) { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val file = aqi.upload(roomId, png).body<FileMeta>()
        val photo = aqi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "image", "我们的书架", fileId = file.id)).body<Message>()
        val reply = xiaochi.message(roomId, "真好", replyTo = photo.id)
        val parent = aqi.todo(roomId, "搬家")
        val child = aqi.todo(roomId, "打包书", parent = parent.id)
        val since = child.seq

        aqi.remove("/api/v1/rooms/$roomId/messages/${photo.id}")
        aqi.remove("/api/v1/rooms/$roomId/todos/${parent.id}")
        aqi.delete("/api/v1/rooms/$roomId/trash/todo/${child.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        assertEquals(HttpStatusCode.NoContent, xiaochi.delete("/api/v1/rooms/$roomId/trash/message/${photo.id}").status)
        assertEquals(HttpStatusCode.NoContent, xiaochi.delete("/api/v1/rooms/$roomId/trash/todo/${parent.id}").status)
        assertTrue(aqi.trash(roomId).items.isEmpty())
        aqi.delete("/api/v1/rooms/$roomId/trash/message/${photo.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.get("/api/v1/files/${file.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val changes = aqi.get("/api/v1/rooms/$roomId/sync?since=$since").body<SyncResponse>().changes.associateBy { it.id }
        assertEquals(ChangeOp.Delete, changes.getValue(photo.id).op)
        assertEquals(ChangeOp.Delete, changes.getValue(parent.id).op)
        assertEquals(ChangeOp.Delete, changes.getValue(child.id).op)
        val updatedReply = QichiJson.decodeFromJsonElement(Message.serializer(), changes.getValue(reply.id).data!!)
        assertNull(updatedReply.replyExcerpt)
        assertNull(updatedReply.replyToId)
    }
}
