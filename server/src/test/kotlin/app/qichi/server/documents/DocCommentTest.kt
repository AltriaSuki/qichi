package app.qichi.server.documents

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateDocCommentRequest
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.Document
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateDocCommentRequest
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

/** P9-03：文稿段落旁的留言。 */
class DocCommentTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `留言钉在原文上，对方回复、标为解决、重新打开；同 id 重试不多出来；对方从 bootstrap 拿到`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val docs = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(docs, CreateDocumentRequest(UuidV7.generate(), "海边周末")).body<Document>()
        aqi.post("$docs/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, "周六早上八点出发。\n\n晚上吃海鲜。"))

        val req = CreateDocCommentRequest(UuidV7.generate(), " 八点会不会太早？ ", quote = "周六早上八点出发。", version = 1)
        val first = aqi.post("$docs/${doc.id}/comments", req)
        assertEquals(HttpStatusCode.Created, first.status)
        val root = first.body<DocComment>()
        assertEquals("八点会不会太早？", root.body)
        assertEquals("周六早上八点出发。", root.quote)
        assertEquals(1, root.version)
        assertEquals(HttpStatusCode.OK, aqi.post("$docs/${doc.id}/comments", req).status, "重试")

        val reply = chi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "那就九点", parentId = root.id)).body<DocComment>()
        assertEquals(root.id, reply.parentId)
        assertNull(reply.quote)

        val resolved = chi.post("/api/v1/rooms/$room/doc-comments/${root.id}/resolve").body<DocComment>()
        assertNotNull(resolved.resolvedAt)
        assertEquals(resolved.resolvedBy, reply.authorId)
        assertNull(aqi.post("/api/v1/rooms/$room/doc-comments/${root.id}/reopen").body<DocComment>().resolvedAt)

        val seen = chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().docComments
        assertEquals(setOf(root.id, reply.id), seen.map { it.id }.toSet())
    }

    @Test fun `规则：开头要有原文、回复不能再被回复；只有作者能改和删；回复不能单独删；非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val docs = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(docs, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()
        aqi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "没钉原文"))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val root = aqi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "这里", quote = "第一段")).body<DocComment>()
        val reply = chi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "好", parentId = root.id)).body<DocComment>()
        chi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "套娃", parentId = reply.id))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        chi.patch("/api/v1/rooms/$room/doc-comments/${root.id}", UpdateDocCommentRequest("改别人的")).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertEquals("这一段", aqi.patch("/api/v1/rooms/$room/doc-comments/${root.id}", UpdateDocCommentRequest("这一段")).body<DocComment>().body)
        chi.delete("/api/v1/rooms/$room/doc-comments/${root.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        chi.delete("/api/v1/rooms/$room/doc-comments/${reply.id}").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val outsider = api.outsider(aqi)
        outsider.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "x", quote = "y"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("/api/v1/rooms/$room/doc-comments/${root.id}/resolve").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `删除进回收站可恢复；彻底删除连回复一起删；文稿彻底删除时留言也记下删除`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val docs = "/api/v1/rooms/$room/documents"
        val doc = aqi.post(docs, CreateDocumentRequest(UuidV7.generate(), "信")).body<Document>()
        val root = aqi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "这里", quote = "第一段")).body<DocComment>()
        val reply = chi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "好", parentId = root.id)).body<DocComment>()

        assertNotNull(aqi.delete("/api/v1/rooms/$room/doc-comments/${root.id}").body<DocComment>().deletedAt)
        assertEquals(listOf(TrashType.DocComment to root.id), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type to it.id })
        aqi.post("/api/v1/rooms/$room/trash/doc_comment/${root.id}/restore")
        assertNull(chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().docComments.single { it.id == root.id }.deletedAt)

        aqi.delete("/api/v1/rooms/$room/doc-comments/${root.id}")
        chi.delete("/api/v1/rooms/$room/trash/doc_comment/${root.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/doc_comment/${root.id}").status)
        val deletes = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
            .filter { it.type == EntityType.DocComment && it.op == ChangeOp.Delete }.map { it.id }.toSet()
        assertEquals(setOf(root.id, reply.id), deletes)

        // 文稿在回收站时，它的留言不单独列出；彻底删除文稿时留言逐条记下删除
        val other = aqi.post("$docs/${doc.id}/comments", CreateDocCommentRequest(UuidV7.generate(), "再来", quote = "第二段")).body<DocComment>()
        aqi.delete("/api/v1/rooms/$room/doc-comments/${other.id}")
        chi.delete("$docs/${doc.id}")
        assertEquals(listOf(TrashType.Document), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        aqi.delete("/api/v1/rooms/$room/trash/document/${doc.id}")
        val last = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.last { it.id == other.id }
        assertEquals(ChangeOp.Delete, last.op)
        assertTrue(aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().docComments.isEmpty())
    }
}
