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
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun Application.installWebSockets() {
    install(WebSockets) {
        pingPeriod = 60.seconds
        timeout = 120.seconds
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
         * 带 `?caps=notify` 的连接还会收到发给自己的 notify（内置通知，App 在后台时弹出）；
         * 带 `ai_stream` 的还会收到 ai.delta（问 AI 边生成边显示）。能力用逗号分开，如 `?caps=notify,ai_stream`。
         */
        webSocket("/ws") {
            ctx.realtime.connectionOpened()
            try {
                realtimeSession(ctx)
            } finally {
                ctx.realtime.connectionClosed()
            }
        }
    }
}

/**
 * 一条实时连接从握手到结束。结束的时候有三种：手机断开（正常关闭、断网、心跳超时）、这次登录被作废、转发出错。
 * 以前只等「登录被作废」：客户端不发消息、这里也不读，手机断开后这个处理一直挂着，
 * 直到服务重启或这次登录被作废才结束，线上一台手机一天能积下几十个（P21-15）。
 */
private suspend fun DefaultWebSocketServerSession.realtimeSession(ctx: AppContext) {
    val userId = call.user.userId
    val familyId = call.user.familyId
    val caps = call.request.queryParameters["caps"].orEmpty().split(',').map { it.trim() }.toSet()
    val rooms = ConcurrentHashMap.newKeySet<UUID>()
    val hello = ctx.database.tx(readOnly = true) {
        val roomIds = RoomMembers.select(RoomMembers.roomId)
            .where { (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
            .map { it[RoomMembers.roomId] }
        rooms += roomIds
        WsEvent.Hello(userId, roomIds.map { RoomSeq(it, RoomRepository.lastSeq(it)) })
    }
    send(Frame.Text(QichiJson.encodeToString(WsEvent.serializer(), hello)))

    val ended = CompletableDeferred<Unit>()
    var revoked = false
    coroutineScope {
        // 房间里的变化转给手机
        launch {
            try {
                ctx.realtime.events
                    .filter { event -> (event.userId == null || event.userId == userId) && (event.cap == null || event.cap in caps) }
                    .filter { event ->
                        // 连接期间新加入的房间：第一次收到它的事件时查一次成员身份
                        event.roomId in rooms || ctx.database.tx(readOnly = true) {
                            RoomRepository.isMember(event.roomId, userId)
                        }.also { if (it) rooms += event.roomId }
                    }
                    .onEach { event ->
                        send(Frame.Text(QichiJson.encodeToString(WsEvent.serializer(), event.event)))
                    }
                    .collect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 连接已经断了（发不出去）或查库出错：结束这条连接，手机会重连
            } finally {
                ended.complete(Unit)
            }
        }
        // 这次登录被作废（登出、改密码、踢设备、刷新令牌被盗用）就马上断开，不再收任何通知（P13-09）；
        // 开始等之后再核对一次，握手之后、开始等之前被作废的也不漏
        launch {
            ctx.realtime.revocations
                .onSubscription {
                    if (!ctx.auth.isSessionActive(userId, familyId)) emit(SessionsRevoked(userId, setOf(familyId)))
                }
                .first { it.userId == userId && familyId in it.familyIds }
            revoked = true
            ended.complete(Unit)
        }
        // 客户端不发业务消息：只读到连接结束为止（正常关闭、断网、心跳超时都会让这里结束）
        launch {
            try {
                for (frame in incoming) Unit
            } finally {
                ended.complete(Unit)
            }
        }
        ended.await()
        coroutineContext.cancelChildren()
    }
    if (revoked) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "登录已失效"))
}
