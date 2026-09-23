package app.qichi.server.documents

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.DocumentVersionPage
import app.qichi.shared.api.Problem
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateDocumentRequest
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

class DocumentTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `新建文稿：幂等，没有版本；对方从 bootstrap 和 sync 里拿到；两人都能改标题`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/documents"
        val request = CreateDocumentRequest(UuidV7.generate(), "  给明年秋天的信 ")
        val created = aqi.post(path, request)
        assertEquals(HttpStatusCode.Created, created.status)
        val doc = created.body<Document>()
        assertEquals("给明年秋天的信", doc.title)
        assertEquals(0, doc.latestVersion)
        assertNull(doc.latestAuthorId)
        assertEquals(HttpStatusCode.OK, aqi.post(path, request).status)

        assertEquals(listOf(doc.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().documents.map { it.id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.any { it.type == EntityType.Document && it.id == doc.id })
        assertEquals(listOf(doc.id), chi.get(path).body<List<Document>>().map { it.id })

        val renamed = chi.patch("$path/${doc.id}", UpdateDocumentRequest("给明年的信")).body<Document>()
        assertEquals("给明年的信", renamed.title)
        assertTrue(renamed.seq > doc.seq)
        chi.patch("$path/${doc.id}", UpdateDocumentRequest(" ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `保存版本：基于最新版本才能保存，版本号递增，文稿记下最新作者和字数；同 id 重试不会多出版本`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(path, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()

        val first = SaveDocumentVersionRequest(UuidV7.generate(), baseVersion = 0, body = "## 我们\n窗边的绿萝又长了一截。")
        val saved = aqi.post("$path/${doc.id}/versions", first)
        assertEquals(HttpStatusCode.Created, saved.status)
        val v1 = saved.body<DocumentVersion>()
        assertEquals(1, v1.version)
        assertEquals(0, v1.baseVersion)
        assertEquals(aqi.userId(), v1.authorId)
        assertEquals(app.qichi.shared.util.CjkText.charCount(first.body), v1.charCount)
        // 重试同一个请求：还是 v1
        val retry = aqi.post("$path/${doc.id}/versions", first)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(v1.id, retry.body<DocumentVersion>().id)

        val v2 = chi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, "## 我们\n窗边的绿萝又长了一截。你说要去看海。")).body<DocumentVersion>()
        assertEquals(2, v2.version)

        val latest = chi.get(path).body<List<Document>>().single()
        assertEquals(2, latest.latestVersion)
        assertEquals(chi.userId(), latest.latestAuthorId)
        assertEquals(v2.charCount, latest.charCount)

        assertEquals(v1.body, aqi.get("$path/${doc.id}/versions/1").body<DocumentVersion>().body)
        aqi.get("$path/${doc.id}/versions/3").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `基线落后返回 409 conflict_version，并附上最新版本号；旧版可以另存为新版`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(path, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()
        aqi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, "第一稿"))
        chi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, "第二稿"))

        // 阿栖还停在 v1 上写：被拒绝，拿到最新版本号 2
        val stale = aqi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, "第一稿，改了改"))
        stale.assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictVersion)
        assertEquals(2, stale.body<Problem>().latestVersion)
        assertEquals(2, aqi.get(path).body<List<Document>>().single().latestVersion, "被拒绝的保存不产生版本")

        // 把 v1 另存为 v3
        val restored = aqi.post(
            "$path/${doc.id}/versions",
            SaveDocumentVersionRequest(UuidV7.generate(), 2, "第一稿", restoredFromVersion = 1),
        ).body<DocumentVersion>()
        assertEquals(3, restored.version)
        assertEquals(1, restored.restoredFromVersion)
        aqi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 3, "x", restoredFromVersion = 7))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `版本列表不含正文，从新到旧分页`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(path, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()
        repeat(5) { i -> aqi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), i, "第 ${i + 1} 稿")) }
        val first = aqi.get("$path/${doc.id}/versions?limit=2")
        assertTrue("body" !in first.body<String>(), "列表里不带正文")
        val page1 = first.body<DocumentVersionPage>()
        assertEquals(listOf(5, 4), page1.items.map { it.version })
        val page2 = aqi.get("$path/${doc.id}/versions?limit=2&cursor=${page1.nextCursor}").body<DocumentVersionPage>()
        assertEquals(listOf(3, 2), page2.items.map { it.version })
        val page3 = aqi.get("$path/${doc.id}/versions?limit=2&cursor=${page2.nextCursor}").body<DocumentVersionPage>()
        assertEquals(listOf(1), page3.items.map { it.version })
        assertNull(page3.nextCursor)
    }

    @Test fun `非成员一律 404；删除后进回收站，读不到版本，恢复后又能读；彻底删除连版本一起删`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val path = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(path, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()
        aqi.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, "第一稿"))
        val outsider = api.outsider(aqi)
        outsider.get(path).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.get("$path/${doc.id}/versions/1").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("$path/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, "x"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val deleted = chi.delete("$path/${doc.id}").body<Document>()
        assertNotNull(deleted.deletedAt)
        assertTrue(aqi.get(path).body<List<Document>>().isEmpty())
        aqi.get("$path/${doc.id}/versions/1").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertEquals(listOf(TrashType.Document to doc.id), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type to it.id })

        aqi.post("/api/v1/rooms/$room/trash/document/${doc.id}/restore")
        assertEquals("第一稿", aqi.get("$path/${doc.id}/versions/1").body<DocumentVersion>().body)

        chi.delete("$path/${doc.id}")
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/document/${doc.id}").status)
        val last = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.last { it.id == doc.id }
        assertEquals(ChangeOp.Delete, last.op)
    }
}
