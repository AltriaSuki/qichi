package app.qichi.server.auth

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.json
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.DeleteAccountRequest
import app.qichi.shared.api.Invite
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.RoomDetail
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 退出房间与注销账号（P16-07）：内容留在房间，署名「已注销的成员」；只剩一人的房间一起删掉。 */
class AccountDeleteTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private suspend fun Session.deleteAccount(password: String): HttpResponse =
        api.client.delete("/api/v1/me") { bearerAuth(tokens.accessToken); json(DeleteAccountRequest(password)) }

    private suspend fun Session.send(roomId: java.util.UUID, body: String): Message =
        post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", body)).body()

    @Test
    fun `注销后不能登录；写过的内容留在房间，署名已注销的成员；另一个人照常用`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val memberId = member.userId()
        val said = member.send(roomId, "周六见")

        member.deleteAccount("wrong-password").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        assertEquals(HttpStatusCode.NoContent, member.deleteAccount("password-xiaochi").status)

        member.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.login("xiaochi").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)

        val boot = owner.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>()
        val gone = boot.members.single { it.userId == memberId }
        assertNotNull(gone.deletedAt, "不再是房间成员")
        assertEquals("已注销的成员", gone.displayName)
        val messages = owner.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages
        assertEquals(memberId, messages.single { it.id == said.id }.authorId, "内容还在，作者没变")

        // 另一个人照常发消息、还能邀请新的人
        owner.send(roomId, "好")
        assertEquals(HttpStatusCode.Created, owner.post("/api/v1/rooms/$roomId/invites").status)
        // 原来的用户名空出来了
        val invite = owner.post("/api/v1/rooms/$roomId/invites").body<Invite>()
        assertEquals(HttpStatusCode.Created, api.register("xiaochi", invite.code).status)
    }

    @Test
    fun `只剩自己的房间随账号一起删掉`() = serverTest { client ->
        val api = Api(client)
        val (owner, _, _) = api.pair()
        val solo = owner.createRoom("只有我").room.id
        val invite = owner.post("/api/v1/rooms/$solo/invites").body<Invite>()
        assertEquals(HttpStatusCode.NoContent, owner.deleteAccount("password-aqi").status)
        // 房间连同邀请码都没了
        api.register("newcomer", invite.code).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InviteInvalid)
    }

    @Test
    fun `退出房间：房间和内容留给另一个人；只剩自己时不能退出`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val said = member.send(roomId, "我先走了")
        assertEquals(HttpStatusCode.NoContent, member.post("/api/v1/rooms/$roomId/leave").status)
        member.get("/api/v1/rooms/$roomId").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        member.get("/api/v1/me").body<app.qichi.shared.api.Me>().let { assertTrue(it.rooms.none { r -> r.roomId == roomId }) }

        val detail = owner.get("/api/v1/rooms/$roomId").body<RoomDetail>()
        assertEquals(1, detail.members.size)
        assertTrue(owner.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.any { it.id == said.id })
        owner.post("/api/v1/rooms/$roomId/leave").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }
}
