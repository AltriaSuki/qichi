package app.qichi.server.messages

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.EditMessageRequest
import app.qichi.shared.api.Message
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.SetMessageReactionRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 改自己发的消息、给消息回应（P16-05）。 */
class MessageEditReactTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun Session.send(roomId: UUID, body: String, replyTo: UUID? = null): Message =
        post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", body, replyToId = replyTo)).body()

    private suspend fun Session.syncedMessage(roomId: UUID, id: UUID): Message =
        get("/api/v1/rooms/$roomId/sync?since=0").body<SyncResponse>().changes
            .single { it.type == EntityType.Message && it.id == id }
            .let { QichiJson.decodeFromJsonElement<Message>(it.data!!) }

    @Test
    fun `改：只有作者、24 小时内；标上已编辑，回复里的摘要跟着换，同步里是新的`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            val original = aqi.send(roomId, "今晚吃火锅")
            val reply = xiaochi.send(roomId, "好", replyTo = original.id)
            assertEquals("今晚吃火锅", reply.replyExcerpt)

            xiaochi.patch("/api/v1/rooms/$roomId/messages/${original.id}", EditMessageRequest("改成烧烤"))
                .assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
            aqi.patch("/api/v1/rooms/$roomId/messages/${original.id}", EditMessageRequest("   "))
                .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

            val edited = aqi.patch("/api/v1/rooms/$roomId/messages/${original.id}", EditMessageRequest(" 今晚吃烧烤 ")).body<Message>()
            assertEquals("今晚吃烧烤", edited.body)
            assertNotNull(edited.editedAt)
            assertEquals(original.createdSeq, edited.createdSeq)
            assertEquals("今晚吃烧烤", xiaochi.syncedMessage(roomId, original.id).body)
            assertEquals("今晚吃烧烤", xiaochi.syncedMessage(roomId, reply.id).replyExcerpt)

            // 内容没变：不产生新的变化
            val again = aqi.patch("/api/v1/rooms/$roomId/messages/${original.id}", EditMessageRequest("今晚吃烧烤")).body<Message>()
            assertEquals(edited.seq, again.seq)

            clock.advance(Duration.ofHours(25))
            val relogged = Api(client).loginOk("aqi")
            relogged.patch("/api/v1/rooms/$roomId/messages/${original.id}", EditMessageRequest("明天吃"))
                .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        }
    }

    @Test
    fun `撤回的、不是文字的、别的房间的都不能改`() = serverTest { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val m = aqi.send(roomId, "说错了")
        aqi.post("/api/v1/rooms/$roomId/messages/${m.id}/retract")
        aqi.patch("/api/v1/rooms/$roomId/messages/${m.id}", EditMessageRequest("x")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.patch("/api/v1/rooms/${UuidV7.generate()}/messages/${m.id}", EditMessageRequest("x")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.patch("/api/v1/rooms/$roomId/messages/${UuidV7.generate()}", EditMessageRequest("x")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `回应：每人一个，换一种就是改，null 收回；重复提交不产生变化；撤回的不能回应`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val aqiId = aqi.userId()
        val xiaochiId = xiaochi.userId()
        val m = aqi.send(roomId, "今天升职了")
        val path = "/api/v1/rooms/$roomId/messages/${m.id}/reaction"

        val liked = xiaochi.put(path, SetMessageReactionRequest(BoardReactionKind.Like)).body<Message>()
        assertEquals(mapOf(xiaochiId to BoardReactionKind.Like), liked.reactions)
        assertEquals(liked.seq, xiaochi.put(path, SetMessageReactionRequest(BoardReactionKind.Like)).body<Message>().seq)

        aqi.put(path, SetMessageReactionRequest(BoardReactionKind.Hug))
        val changed = xiaochi.put(path, SetMessageReactionRequest(BoardReactionKind.Support)).body<Message>()
        assertEquals(mapOf(aqiId to BoardReactionKind.Hug, xiaochiId to BoardReactionKind.Support), changed.reactions)
        assertEquals(changed.reactions, aqi.syncedMessage(roomId, m.id).reactions)
        // 回应不算编辑
        assertNull(changed.editedAt)

        val removed = xiaochi.put(path, SetMessageReactionRequest(null)).body<Message>()
        assertEquals(mapOf(aqiId to BoardReactionKind.Hug), removed.reactions)

        aqi.post("/api/v1/rooms/$roomId/messages/${m.id}/retract")
        xiaochi.put(path, SetMessageReactionRequest(BoardReactionKind.Like)).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        // 撤回时回应一起清掉
        assertTrue(aqi.syncedMessage(roomId, m.id).reactions.isEmpty())
    }
}
