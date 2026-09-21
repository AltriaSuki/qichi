package app.qichi.server.messages

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Message
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadMarkerTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun Session.send(roomId: UUID, body: String): Message =
        post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", body)).body()

    private suspend fun Session.mark(roomId: UUID, seq: Long): ReadMarker {
        val response = put("/api/v1/rooms/$roomId/read-marker", UpdateReadMarkerRequest(seq))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    @Test
    fun `只进不退，不超过最新一条消息`() = serverTest { client ->
        val (aqi, xiaochi, roomId) = Api(client).pair()
        val first = xiaochi.send(roomId, "一")
        val second = xiaochi.send(roomId, "二")

        val marker = aqi.mark(roomId, first.createdSeq)
        assertEquals(first.createdSeq, marker.lastReadSeq)
        assertEquals(aqi.userId(), marker.userId)

        assertEquals(second.createdSeq, aqi.mark(roomId, second.createdSeq).lastReadSeq)
        val stale = aqi.mark(roomId, first.createdSeq)
        assertEquals(second.createdSeq, stale.lastReadSeq, "旧值不会覆盖新值")
        assertEquals(marker.id, stale.id, "每人每房间一条")

        assertEquals(second.createdSeq, aqi.mark(roomId, 1_000_000).lastReadSeq, "不超过最新一条消息")

        aqi.put("/api/v1/rooms/$roomId/read-marker", UpdateReadMarkerRequest(-1))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `未读位置只同步给自己`() = serverTest { client ->
        val api = Api(client)
        val (aqi, xiaochi, roomId) = api.pair()
        val message = xiaochi.send(roomId, "在吗")
        aqi.mark(roomId, message.createdSeq)

        val mine = aqi.get("/api/v1/rooms/$roomId/sync?since=0").body<SyncResponse>()
        assertTrue(mine.changes.any { it.type == EntityType.ReadMarker })
        val theirs = xiaochi.get("/api/v1/rooms/$roomId/sync?since=0").body<SyncResponse>()
        assertTrue(theirs.changes.none { it.type == EntityType.ReadMarker })

        api.outsider(aqi).put("/api/v1/rooms/$roomId/read-marker", UpdateReadMarkerRequest(1))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }
}
