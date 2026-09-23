package app.qichi.server.ideas

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.Change
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.Idea
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateIdeaRequest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeaTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `记下灵感：幂等创建，对方能同步到；只有作者能改`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/ideas"
        val request = CreateIdeaRequest(UuidV7.generate(), "  阳台可以种一棵柠檬树 ")
        val created = aqi.post(path, request)
        assertEquals(HttpStatusCode.Created, created.status)
        val idea = created.body<Idea>()
        assertEquals("阳台可以种一棵柠檬树", idea.body)
        assertEquals(aqi.userId(), idea.authorId)
        assertEquals(HttpStatusCode.OK, aqi.post(path, request).status)
        chi.post(path, request).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)

        assertEquals(listOf(idea.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().ideas.map { it.id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.any { it.type == EntityType.Idea && it.id == idea.id })

        val updated = aqi.patch("$path/${idea.id}", UpdateIdeaRequest("阳台种柠檬树，还要一把小椅子")).body<Idea>()
        assertEquals("阳台种柠檬树，还要一把小椅子", updated.body)
        assertTrue(updated.seq > idea.seq)
        chi.patch("$path/${idea.id}", UpdateIdeaRequest("x")).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
    }

    @Test fun `参数与权限：空内容、超长 400，非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        val path = "/api/v1/rooms/$room/ideas"
        aqi.post(path, CreateIdeaRequest(UuidV7.generate(), "   ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post(path, CreateIdeaRequest(UuidV7.generate(), "字".repeat(2001))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        assertEquals(HttpStatusCode.Created, aqi.post(path, CreateIdeaRequest(UuidV7.generate(), "字".repeat(2000))).status)
        api.outsider(aqi).post(path, CreateIdeaRequest(UuidV7.generate(), "hi")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `两个人都能删除；进回收站后可恢复，也可彻底删除`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/ideas"
        val idea = aqi.post(path, CreateIdeaRequest(UuidV7.generate(), "周末去看展")).body<Idea>()
        val deleted = chi.delete("$path/${idea.id}").body<Idea>()
        assertNotNull(deleted.deletedAt)
        assertEquals(chi.userId(), deleted.deletedBy)
        val trash = aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>()
        assertEquals(listOf(TrashType.Idea to idea.id), trash.items.map { it.type to it.id })

        val restored = aqi.post("/api/v1/rooms/$room/trash/idea/${idea.id}/restore").body<Change>()
        assertNull(QichiJson.decodeFromJsonElement(Idea.serializer(), restored.data!!).deletedAt)

        chi.delete("$path/${idea.id}")
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/idea/${idea.id}").status)
        val last = aqi.get("/api/v1/rooms/$room/sync?since=${restored.seq}").body<SyncResponse>().changes.last { it.id == idea.id }
        assertEquals(ChangeOp.Delete, last.op)
    }
}
