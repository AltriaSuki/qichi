package app.qichi.server.sync

import app.qichi.server.db.ChangeNotifier
import app.qichi.shared.api.PushPayload
import app.qichi.shared.api.WsEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** 一条发给房间成员的实时事件（changed、ai.done）；[userId] 不为空时只发给这个人（notify）。 */
data class RoomEvent(val roomId: UUID, val event: WsEvent, val userId: UUID? = null)

/**
 * 进程内的实时事件总线：RoomWriter 提交后发布 changed，AI 任务结束时发布 ai.done；
 * WebSocket 连接各自订阅并过滤自己所在的房间。
 * 事件只是提示（客户端收到后调用 sync 拉取），所以缓冲满了可以丢最旧的。
 */
class RealtimeHub : ChangeNotifier {
    private val flow = MutableSharedFlow<RoomEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<RoomEvent> = flow.asSharedFlow()

    override suspend fun roomChanged(roomId: UUID, seq: Long) {
        flow.emit(RoomEvent(roomId, WsEvent.Changed(roomId, seq)))
    }

    suspend fun aiDone(roomId: UUID, jobId: UUID, status: String) {
        flow.emit(RoomEvent(roomId, WsEvent.AiDone(roomId, jobId, status)))
    }

    /** 内置通知：只发给 [userId] 带了 caps=notify 的连接。 */
    suspend fun notify(roomId: UUID, userId: UUID, payload: PushPayload) {
        flow.emit(RoomEvent(roomId, WsEvent.Notify(roomId, payload), userId))
    }
}
