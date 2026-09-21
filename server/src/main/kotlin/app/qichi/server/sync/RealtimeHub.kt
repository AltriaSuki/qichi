package app.qichi.server.sync

import app.qichi.server.db.ChangeNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** 一条实时事件：房间 [roomId] 有了新的 [seq]。 */
data class RoomChanged(val roomId: UUID, val seq: Long)

/**
 * 进程内的实时事件总线：RoomWriter 提交后发布，WebSocket 连接各自订阅并过滤自己所在的房间。
 * 事件只是提示（客户端收到后调用 sync 拉取），所以缓冲满了可以丢最旧的。
 */
class RealtimeHub : ChangeNotifier {
    private val flow = MutableSharedFlow<RoomChanged>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<RoomChanged> = flow.asSharedFlow()

    override suspend fun roomChanged(roomId: UUID, seq: Long) {
        flow.emit(RoomChanged(roomId, seq))
    }
}
