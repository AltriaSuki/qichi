package app.qichi.server.board

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardPostRevision
import app.qichi.shared.api.BoardReaction
import app.qichi.shared.api.BoardSearchResult
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateBoardPostRequest
import app.qichi.shared.api.CreateBoardTopicRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Problem
import app.qichi.shared.api.PutBoardReactionRequest
import app.qichi.shared.api.ReviseBoardPostRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateBoardTopicRequest
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

class BoardTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `主题与留言：幂等创建，对方能同步到；置顶的排在前面`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room/board"
        val req = CreateBoardTopicRequest(UuidV7.generate(), "  关于搬家 ")
        val created = aqi.post("$base/topics", req)
        assertEquals(HttpStatusCode.Created, created.status)
        val moving = created.body<BoardTopic>()
        assertEquals("关于搬家", moving.title)
        assertEquals(HttpStatusCode.OK, aqi.post("$base/topics", req).status)
        val travel = chi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "旅行")).body<BoardTopic>()

        val postReq = CreateBoardPostRequest(UuidV7.generate(), "我想了很久，还是觉得离你公司近一点比较好。")
        val first = aqi.post("$base/topics/${moving.id}/posts", postReq)
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, aqi.post("$base/topics/${moving.id}/posts", postReq).status)
        chi.post("$base/topics/${moving.id}/posts", postReq).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)

        val boot = chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>()
        assertEquals(setOf(moving.id, travel.id), boot.boardTopics.map { it.id }.toSet())
        assertEquals(listOf(postReq.id), boot.boardPosts.map { it.id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.any { it.type == EntityType.BoardPost })

        // 有新留言的主题在前；置顶之后「旅行」排到最前
        assertEquals(listOf(moving.id, travel.id), aqi.get("$base/topics").body<List<BoardTopic>>().map { it.id })
        val pinned = aqi.patch("$base/topics/${travel.id}", UpdateBoardTopicRequest(pinned = Patch.of(true))).body<BoardTopic>()
        assertNotNull(pinned.pinnedAt)
        assertEquals(listOf(travel.id, moving.id), aqi.get("$base/topics").body<List<BoardTopic>>().map { it.id })
        assertNull(chi.patch("$base/topics/${travel.id}", UpdateBoardTopicRequest(pinned = Patch.of(false))).body<BoardTopic>().pinnedAt)
    }

    @Test fun `引用回复带摘录；只有作者能修订，基线落后 409，旧内容进修订历史`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room/board"
        val topic = aqi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "关于搬家")).body<BoardTopic>()
        val long = "## 我的想法\n\n" + "离你公司近一点，".repeat(20)
        val first = aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), long)).body<BoardPost>()
        val reply = chi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "我同意，但房租要算清楚。", quotePostId = first.id)).body<BoardPost>()
        assertEquals(first.id, reply.quotePostId)
        assertEquals(aqi.userId(), reply.quoteAuthorId)
        val excerpt = assertNotNull(reply.quoteExcerpt)
        assertTrue(excerpt.startsWith("我的想法 离你公司近一点"))
        assertEquals(80, excerpt.length)
        assertTrue(excerpt.endsWith("…"))

        chi.patch("$base/posts/${first.id}", ReviseBoardPostRequest(1, "x")).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        val revised = aqi.patch("$base/posts/${first.id}", ReviseBoardPostRequest(1, "还是离你公司近一点吧。")).body<BoardPost>()
        assertEquals(2, revised.revision)
        assertNotNull(revised.revisedAt)
        val stale = aqi.patch("$base/posts/${first.id}", ReviseBoardPostRequest(1, "另一台手机上的修改"))
        stale.assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictVersion)
        assertEquals(2, stale.body<Problem>().latestVersion)

        val history = chi.get("$base/posts/${first.id}/revisions").body<List<BoardPostRevision>>()
        assertEquals(listOf(1), history.map { it.revision })
        assertEquals(long, history.single().body)
        // 引用的摘录不随原文修订而改变
        assertEquals(excerpt, chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().boardPosts.first { it.id == reply.id }.quoteExcerpt)
    }

    @Test fun `回应：同一种回应只有一条，收回后能再回应；不能回应自己的`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room/board"
        val topic = aqi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "近况")).body<BoardTopic>()
        val post = aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "这周有点累。")).body<BoardPost>()

        val hug = chi.put("$base/posts/${post.id}/reactions/hug", PutBoardReactionRequest(UuidV7.generate()))
        assertEquals(HttpStatusCode.Created, hug.status)
        val first = hug.body<BoardReaction>()
        // 另一个 id 再点一次同一种：返回已有的
        val again = chi.put("$base/posts/${post.id}/reactions/hug", PutBoardReactionRequest(UuidV7.generate()))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first.id, again.body<BoardReaction>().id)
        aqi.put("$base/posts/${post.id}/reactions/like", PutBoardReactionRequest(UuidV7.generate()))
            .assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        chi.put("$base/posts/${post.id}/reactions/wave", PutBoardReactionRequest(UuidV7.generate()))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        assertNotNull(chi.delete("$base/posts/${post.id}/reactions/hug").body<BoardReaction>().deletedAt)
        chi.delete("$base/posts/${post.id}/reactions/hug").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertEquals(HttpStatusCode.Created, chi.put("$base/posts/${post.id}/reactions/hug", PutBoardReactionRequest(UuidV7.generate())).status)
    }

    @Test fun `搜索正文和标题；删掉的主题下的留言搜不到`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val base = "/api/v1/rooms/$room/board"
        val topic = aqi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "旅行计划")).body<BoardTopic>()
        aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "想去海边的小镇住两晚"))
        aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "100% 要带相机"))
        val result = aqi.get("$base/search?q=海边").body<BoardSearchResult>()
        assertEquals(listOf("想去海边的小镇住两晚"), result.posts.map { it.body })
        assertEquals(listOf(topic.id), aqi.get("$base/search?q=旅行").body<BoardSearchResult>().topics.map { it.id })
        assertEquals(1, aqi.get("$base/search?q=100%25").body<BoardSearchResult>().posts.size, "% 按字面匹配")
        aqi.get("$base/search?q=%20").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        aqi.delete("$base/topics/${topic.id}")
        assertTrue(aqi.get("$base/search?q=海边").body<BoardSearchResult>().posts.isEmpty())
    }

    @Test fun `删除进回收站；彻底删除主题连同留言和回应，引用它的留言保留摘录；非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val base = "/api/v1/rooms/$room/board"
        val topic = aqi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "近况")).body<BoardTopic>()
        val post = aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "这周有点累。")).body<BoardPost>()
        chi.put("$base/posts/${post.id}/reactions/hug", PutBoardReactionRequest(UuidV7.generate()))
        val other = aqi.post("$base/topics", CreateBoardTopicRequest(UuidV7.generate(), "别的")).body<BoardTopic>()
        val quoting = chi.post("$base/topics/${other.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "接着上面说", quotePostId = post.id)).body<BoardPost>()

        api.outsider(aqi).get("$base/topics").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        // 删一条留言：进回收站，能恢复
        chi.delete("$base/posts/${post.id}")
        assertEquals(listOf(TrashType.BoardPost), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        aqi.post("/api/v1/rooms/$room/trash/board_post/${post.id}/restore")

        // 删主题，再彻底删除
        chi.delete("$base/topics/${topic.id}")
        aqi.post("$base/topics/${topic.id}/posts", CreateBoardPostRequest(UuidV7.generate(), "x")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/board_topic/${topic.id}").status)
        val changes = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
        assertEquals(ChangeOp.Delete, changes.last { it.id == post.id }.op)
        assertTrue(changes.any { it.type == EntityType.BoardReaction && it.op == ChangeOp.Delete })
        val kept = aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().boardPosts.single { it.id == quoting.id }
        assertNull(kept.quotePostId)
        assertEquals("这周有点累。", kept.quoteExcerpt)
    }
}
