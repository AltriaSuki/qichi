package app.qichi.server.db

import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Instant
import java.util.UUID

/** 房间有变化时通知在线的客户端（WebSocket 广播 changed）。 */
/** 一次已经提交的房间写入（推送据此判断要不要通知对方）。 */
data class CommittedChange(
    val roomId: UUID,
    val seq: Long,
    val type: EntityType,
    val entityId: UUID,
    val actorId: UUID?,
    val op: ChangeOp,
)

fun interface ChangeNotifier {
    suspend fun roomChanged(roomId: UUID, seq: Long)

    /** 带上是哪种实体、谁做的；默认不关心。 */
    suspend fun entityChanged(change: CommittedChange) {}
}

/** 同时通知好几个接收方（实时推送给在线的手机、后台推送）。 */
class CompositeNotifier(private vararg val notifiers: ChangeNotifier) : ChangeNotifier {
    override suspend fun roomChanged(roomId: UUID, seq: Long) = notifiers.forEach { it.roomChanged(roomId, seq) }
    override suspend fun entityChanged(change: CommittedChange) = notifiers.forEach { it.entityChanged(change) }
}

/**
 * 房间内每一次写入的固定动作（docs/05-sync-offline.md §2.1），所有 Service 都必须通过它写库：
 * 1. `UPDATE rooms SET last_seq = last_seq + 1 … RETURNING last_seq` 取得新 seq（同时锁住房间行，保证顺序）
 * 2. 插入 change_log
 * 3. 事务提交后广播 changed
 * 调用方拿到返回的 seq，写进实体的 seq 列。
 */
class RoomWriter(private val notifier: ChangeNotifier) {

    fun change(
        tx: Tx,
        roomId: UUID,
        type: EntityType,
        entityId: UUID,
        actorId: UUID?,
        at: Instant,
        op: ChangeOp = ChangeOp.Upsert,
    ): Long {
        val seq = Rooms.updateReturning(listOf(Rooms.lastSeq), where = { Rooms.id eq roomId }) {
            it[lastSeq] = lastSeq + 1L
        }.singleOrNull()?.get(Rooms.lastSeq) ?: error("房间不存在：$roomId")

        ChangeLog.insert {
            it[ChangeLog.roomId] = roomId
            it[ChangeLog.seq] = seq
            it[entityType] = type.wireName
            it[ChangeLog.entityId] = entityId
            it[ChangeLog.op] = op.wireName
            it[ChangeLog.actorId] = actorId
            it[ChangeLog.at] = at
        }

        tx.afterCommit {
            notifier.roomChanged(roomId, seq)
            notifier.entityChanged(CommittedChange(roomId, seq, type, entityId, actorId, op))
        }
        return seq
    }
}
