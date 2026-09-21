package app.qichi.server.sync

import app.qichi.server.AppContext
import app.qichi.server.db.RoomMembers
import app.qichi.server.db.tx
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.server.rooms.RoomRepository
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RoomSeq
import app.qichi.shared.api.WsEvent
import app.qichi.shared.rules.Limits
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun Application.installWebSockets() {
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = 64 * 1024
    }
}

fun Route.syncRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        get("/rooms/{roomId}/bootstrap") {
            call.respond(ctx.sync.bootstrap(call.user.userId, call.uuidParam("roomId")))
        }
        get("/rooms/{roomId}/sync") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: -1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: Limits.SYNC_PAGE_DEFAULT
            call.respond(ctx.sync.sync(call.user.userId, call.uuidParam("roomId"), since, limit))
        }

        /**
         * 实时通道：连上后先发 hello（所在房间与各自 lastSeq），之后每当所在房间有变化就发 changed。
         * 只是提示，客户端收到后自己调用 sync 拉取。客户端不发业务消息。
         */
        webSocket("/ws") {
            val userId = call.user.userId
            val rooms = ConcurrentHashMap.newKeySet<UUID>()
            val hello = ctx.database.tx(readOnly = true) {
                val roomIds = RoomMembers.select(RoomMembers.roomId)
                    .where { (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
                    .map { it[RoomMembers.roomId] }
                rooms += roomIds
                WsEvent.Hello(userId, roomIds.map { RoomSeq(it, RoomRepository.lastSeq(it)) })
            }
            send(Frame.Text(QichiJson.encodeToString(WsEvent.serializer(), hello)))

            ctx.realtime.events
                .filter { event ->
                    // 连接期间新加入的房间：第一次收到它的事件时查一次成员身份
                    event.roomId in rooms || ctx.database.tx(readOnly = true) {
                        RoomRepository.isMember(event.roomId, userId)
                    }.also { if (it) rooms += event.roomId }
                }
                .onEach { event ->
                    val changed = WsEvent.Changed(event.roomId, event.seq)
                    send(Frame.Text(QichiJson.encodeToString(WsEvent.serializer(), changed)))
                }
                .collect()
        }
    }
}
