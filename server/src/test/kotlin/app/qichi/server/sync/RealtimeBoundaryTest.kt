package app.qichi.server.sync

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.Message
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.api.WsEvent
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** 实时通道的边界（P13-09）：登录作废就断开；已读位置的变化只提示本人。 */
class RealtimeBoundaryTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private suspend fun DefaultClientWebSocketSession.next(): WsEvent = withTimeout(5_000) {
        QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
    }

    private suspend fun DefaultClientWebSocketSession.closedBecauseRevoked() {
        val reason = withTimeout(5_000) { closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
    }

    @Test
    fun `踢掉另一台设备：它的 WebSocket 马上断开`() = serverTest { client ->
        val api = Api(client)
        val (_, member, _) = api.pair()
        val phone2 = api.loginOk("xiaochi")
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/api/v1/ws", request = { bearerAuth(phone2.tokens.accessToken) }) {
            next() as WsEvent.Hello
            val other = member.get("/api/v1/me/sessions").body<List<LoginSession>>().single { !it.current }
            assertEquals(HttpStatusCode.NoContent, member.delete("/api/v1/me/sessions/${other.id}").status)
            closedBecauseRevoked()
        }
    }

    @Test
    fun `登出：这次登录的 WebSocket 马上断开`() = serverTest { client ->
        val (_, member, _) = Api(client).pair()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/api/v1/ws", request = { bearerAuth(member.tokens.accessToken) }) {
            next() as WsEvent.Hello
            assertEquals(HttpStatusCode.NoContent, member.post("/api/v1/auth/logout", RefreshRequest(member.tokens.refreshToken)).status)
            closedBecauseRevoked()
        }
    }

    @Test
    fun `改密码：其它登录的 WebSocket 断开，改密码的这台照常收提示`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, room) = api.pair()
        val ownerPhone2 = api.loginOk("aqi")
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/api/v1/ws", request = { bearerAuth(ownerPhone2.tokens.accessToken) }) {
            next() as WsEvent.Hello
            ws.webSocket("/api/v1/ws", request = { bearerAuth(owner.tokens.accessToken) }) {
                next() as WsEvent.Hello
                val changed = owner.post("/api/v1/me/password", ChangePasswordRequest("password-aqi", "new-password-1"))
                assertEquals(HttpStatusCode.OK, changed.status)
                member.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "还在吗"))
                assertEquals(room, (next() as WsEvent.Changed).roomId)
            }
            closedBecauseRevoked()
        }
    }

    @Test
    fun `对方推进已读位置时我收不到 changed；推进的人自己收得到`() = serverTest { client ->
        val (owner, member, room) = Api(client).pair()
        val message = owner.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "晚饭吃什么")).body<Message>()
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/api/v1/ws", request = { bearerAuth(owner.tokens.accessToken) }) {
            val before = (next() as WsEvent.Hello).rooms.single().lastSeq
            ws.webSocket("/api/v1/ws", request = { bearerAuth(member.tokens.accessToken) }) {
                next() as WsEvent.Hello
                assertEquals(HttpStatusCode.OK, member.put("/api/v1/rooms/$room/read-marker", UpdateReadMarkerRequest(message.createdSeq)).status)
                // 推进的人自己的手机收到提示（别的设备要同步已读位置）
                assertEquals(WsEvent.Changed(room, before + 1), next())
            }
            member.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "面"))
            // 我这边收到的第一条提示就是这条消息（before + 2），已读位置那次（before + 1）没有提示过来
            assertEquals(WsEvent.Changed(room, before + 2), next())
        }
    }
}
