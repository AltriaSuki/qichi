package app.qichi.server.archive

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.ArchiveRevision
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.Message
import app.qichi.shared.api.Problem
import app.qichi.shared.api.ReviseArchiveItemRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `新条目：幂等，带来源消息，对方同步得到；第 1 次修订同时存下`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val msg = chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "我不太喜欢太吵的餐厅。")).body<Message>()
        val path = "/api/v1/rooms/$room/archive"
        val req = CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Preference, "  小迟不喜欢吵的餐厅 ", "约饭先挑安静的地方。", sourceMessageId = msg.id)
        val created = aqi.post(path, req)
        assertEquals(HttpStatusCode.Created, created.status)
        val item = created.body<ArchiveItem>()
        assertEquals("小迟不喜欢吵的餐厅", item.title)
        assertEquals(1, item.currentRevision)
        assertEquals(msg.id, item.sourceMessageId)
        assertEquals(HttpStatusCode.OK, aqi.post(path, req).status)

        assertEquals(listOf(item.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().archiveItems.map { it.id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.any { it.type == EntityType.ArchiveItem })
        val history = chi.get("$path/${item.id}/revisions").body<List<ArchiveRevision>>()
        assertEquals(listOf(1), history.map { it.revision })
        assertEquals(msg.id, history.single().sourceMessageId)
    }

    @Test fun `修订：基线落后 409 并附当前修订号；同一次修订重试不重复；历次修订都能看`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/archive"
        val item = aqi.post(path, CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Consensus, "周末至少留一天不安排")).body<ArchiveItem>()
        val rev = ReviseArchiveItemRequest(UuidV7.generate(), 1, "周末至少留一天不安排", "临时有事要提前一天说。")
        val revised = chi.post("$path/${item.id}/revisions", rev)
        assertEquals(HttpStatusCode.Created, revised.status)
        assertEquals(2, revised.body<ArchiveItem>().currentRevision)
        assertEquals(chi.userId(), revised.body<ArchiveItem>().revisedBy)
        val retry = chi.post("$path/${item.id}/revisions", rev)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(2, retry.body<ArchiveItem>().currentRevision)

        val stale = aqi.post("$path/${item.id}/revisions", ReviseArchiveItemRequest(UuidV7.generate(), 1, "周末两天都空着"))
        stale.assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictVersion)
        assertEquals(2, stale.body<Problem>().latestVersion)

        val history = aqi.get("$path/${item.id}/revisions").body<List<ArchiveRevision>>()
        assertEquals(listOf(2, 1), history.map { it.revision })
        assertEquals(listOf("临时有事要提前一天说。", ""), history.map { it.body })
        assertEquals(listOf(chi.userId(), aqi.userId()), history.map { it.authorId })
    }

    @Test fun `参数：标题空白、正文超长、别的房间的来源消息都是 400；非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        val path = "/api/v1/rooms/$room/archive"
        aqi.post(path, CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Boundary, " ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post(path, CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Boundary, "x", "字".repeat(5001)))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val outsider = api.outsider(aqi)
        val otherRoom = outsider.get("/api/v1/me").body<app.qichi.shared.api.Me>().rooms.single().roomId
        val foreign = outsider.post("/api/v1/rooms/$otherRoom/messages", SendMessageRequest(UuidV7.generate(), "text", "hi")).body<Message>()
        aqi.post(path, CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Boundary, "x", sourceMessageId = foreign.id))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        outsider.get(path).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `删除进回收站可恢复；来源消息被彻底删除后条目的来源断开`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val msg = chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "以后吵架先停十分钟。")).body<Message>()
        val path = "/api/v1/rooms/$room/archive"
        val item = aqi.post(path, CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Consensus, "吵架先停十分钟", sourceMessageId = msg.id)).body<ArchiveItem>()

        chi.delete("$path/${item.id}")
        assertTrue(aqi.get(path).body<List<ArchiveItem>>().isEmpty())
        assertEquals(listOf(TrashType.ArchiveItem), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        aqi.post("/api/v1/rooms/$room/trash/archive_item/${item.id}/restore")
        assertEquals(1, aqi.get(path).body<List<ArchiveItem>>().size)

        chi.delete("/api/v1/rooms/$room/messages/${msg.id}")
        assertEquals(HttpStatusCode.NoContent, chi.delete("/api/v1/rooms/$room/trash/message/${msg.id}").status)
        assertNull(aqi.get(path).body<List<ArchiveItem>>().single().sourceMessageId)
        val last = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.last { it.id == item.id }
        assertEquals(ChangeOp.Upsert, last.op)
    }
}
