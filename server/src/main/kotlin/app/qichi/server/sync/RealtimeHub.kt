package app.qichi.server.sync

import app.qichi.server.db.ChangeNotifier
import app.qichi.server.db.CommittedChange
import app.qichi.shared.model.EntityType
import app.qichi.shared.api.PushPayload
import app.qichi.shared.api.WsEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * 一条发给房间成员的实时事件（changed、ai.done）；[userId] 不为空时只发给这个人（notify）；
 * [cap] 不为空时只发给连接时声明了这项能力的连接（旧版 App 不认识的事件）。
 */
data class RoomEvent(val roomId: UUID, val event: WsEvent, val userId: UUID? = null, val cap: String? = null)

/** 这几次登录（family）被作废了：它们的实时连接马上断开（P13-09）。 */
data class SessionsRevoked(val userId: UUID, val familyIds: Set<UUID>)

/**
 * 进程内的实时事件总线：RoomWriter 提交后发布 changed，AI 任务结束时发布 ai.done；
 * WebSocket 连接各自订阅并过滤自己所在的房间。
 * 事件只是提示（客户端收到后调用 sync 拉取），所以缓冲满了可以丢最旧的。
 * 登录被作废时另走 [revocations]（不能丢），连接收到就断开。
 */
class RealtimeHub : ChangeNotifier {
    private val flow = MutableSharedFlow<RoomEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val revoked = MutableSharedFlow<SessionsRevoked>(extraBufferCapacity = 64)

    val events: SharedFlow<RoomEvent> = flow.asSharedFlow()

    /** 登出、改密码、踢设备、刷新令牌被盗用时发出。 */
    val revocations: SharedFlow<SessionsRevoked> = revoked.asSharedFlow()

    suspend fun sessionsRevoked(userId: UUID, familyIds: Set<UUID>) {
        if (familyIds.isNotEmpty()) revoked.emit(SessionsRevoked(userId, familyIds))
    }

    /** changed 在 [entityChanged] 里发：要看是哪种实体。 */
    override suspend fun roomChanged(roomId: UUID, seq: Long) = Unit

    /**
     * 房间有变化就提示在线的成员去拉取。已读位置的变化只提示本人：对方拉取时本来就看不到它，
     * 提示了反而让对方的手机知道「我刚读了」，等于已读回执，还白白唤醒一次（P13-09）。
     */
    override suspend fun entityChanged(change: CommittedChange) {
        val onlyFor = Visibility.hintOnlyFor(change.type, change.actorId)
        flow.emit(RoomEvent(change.roomId, WsEvent.Changed(change.roomId, change.seq), onlyFor))
    }

    suspend fun aiDone(roomId: UUID, jobId: UUID, status: String) {
        flow.emit(RoomEvent(roomId, WsEvent.AiDone(roomId, jobId, status)))
    }

    /** 内置通知：只发给 [userId] 带了 caps=notify 的连接。 */
    suspend fun notify(roomId: UUID, userId: UUID, payload: PushPayload) {
        flow.emit(RoomEvent(roomId, WsEvent.Notify(roomId, payload), userId, CAP_NOTIFY))
    }

    /** 问 AI 边生成边显示：到目前为止的全文（AI 在查资料时是 [status]），发给房间里带了 caps=ai_stream 的连接。 */
    suspend fun aiDelta(roomId: UUID, jobId: UUID, text: String, status: String? = null) {
        flow.emit(RoomEvent(roomId, WsEvent.AiDelta(roomId, jobId, text, status), cap = CAP_AI_STREAM))
    }

    companion object {
        const val CAP_NOTIFY = "notify"
        const val CAP_AI_STREAM = "ai_stream"
    }
}
