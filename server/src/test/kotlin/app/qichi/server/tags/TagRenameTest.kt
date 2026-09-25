package app.qichi.server.tags

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.RenameTagRequest
import app.qichi.shared.api.RenameTagResult
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P10-07：标签改名 / 合并 = 批量改正文，两个人的都改，照常同步。 */
class TagRenameTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `改名：两个人的灵感和档案里的标签连同子标签都跟着变；档案记一次修订；别的标签不动`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val mine = UuidV7.generate()
        val theirs = UuidV7.generate()
        val other = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(mine, "冬天去看雪 #旅行/北方"))
        chi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(theirs, "海边那家书店 #旅行"))
        chi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(other, "#旅行者 这本书"))
        val item = UuidV7.generate()
        chi.post("/api/v1/rooms/$room/archive", CreateArchiveItemRequest(item, ArchiveKind.Preference, "喜欢靠窗 #旅行", "订车票时选靠窗"))
        val before = aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().lastSeq

        val result = aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "出门")).body<RenameTagResult>()
        assertEquals(RenameTagResult(ideas = 2, archiveItems = 1), result)

        val ideas = chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().ideas.associate { it.id to it.body }
        assertEquals("冬天去看雪 #出门/北方", ideas[mine])
        assertEquals("海边那家书店 #出门", ideas[theirs], "对方记的也改了")
        assertEquals("#旅行者 这本书", ideas[other], "别的标签不动")
        val archived = chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().archiveItems.single { it.id == item }
        assertEquals("喜欢靠窗 #出门", archived.title)
        assertEquals(2, archived.currentRevision)
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=$before").body<SyncResponse>().changes.count { it.type == EntityType.Idea } == 2)

        // 再来一次：什么都不变
        assertEquals(RenameTagResult(0, 0), aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "出门")).body<RenameTagResult>())
        // 合并：改成已有的名字
        assertEquals(RenameTagResult(2, 1), aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("#出门", "家")).body<RenameTagResult>())
    }

    @Test fun `不合法的名字 400，非成员 404`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "有 空格")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "旅行")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "旅行/北方")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("a/b/c", "a/b/c/d")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        api.outsider(aqi).post("/api/v1/rooms/$room/tags/rename", RenameTagRequest("旅行", "出门")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }
}
