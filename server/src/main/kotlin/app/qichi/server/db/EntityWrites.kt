package app.qichi.server.db

import app.qichi.server.plugins.ApiException
import app.qichi.server.rooms.RoomRepository
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 同步实体的通用写法（都在 RoomWriter 之上）：幂等创建、修改、软删除、恢复。
 * 所有写入先锁住房间行，同一房间的写入因此串行，「先查后插」不会有竞争。
 */
class EntityWrites(private val writer: RoomWriter, private val clock: Clock) {

    fun now(): Instant = clock.instant()

    /**
     * 幂等创建：同 id 已存在且属于同一房间 → 返回已有的（created = false）；属于别的房间 → 409 conflict_id。
     * @param load 按 id 读实体（不限房间）
     * @param roomOf 已有实体属于哪个房间
     */
    fun <T> create(
        tx: Tx,
        roomId: UUID,
        actorId: UUID,
        type: EntityType,
        id: UUID,
        table: SyncedTable,
        load: (UUID) -> T?,
        insert: SyncedTable.(UpdateBuilder<*>) -> Unit,
    ): Pair<T, Boolean> {
        RoomRepository.lockRoom(roomId)
        val existingRoom = table.select(table.roomId).where { table.id eq id }.singleOrNull()?.get(table.roomId)
        if (existingRoom != null) {
            if (existingRoom != roomId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            return load(id)!! to false
        }
        val now = now()
        val seq = writer.change(tx, roomId, type, id, actorId, now)
        table.insert {
            it[this.id] = id
            it[this.roomId] = roomId
            it[this.seq] = seq
            it[createdAt] = now
            it[updatedAt] = now
            insert(it)
        }
        return load(id)!! to true
    }

    /** 修改：分配新 seq，写入改动的列与 updated_at。 */
    fun update(
        tx: Tx,
        roomId: UUID,
        actorId: UUID,
        type: EntityType,
        id: UUID,
        table: SyncedTable,
        block: SyncedTable.(UpdateBuilder<*>) -> Unit,
    ) {
        val now = now()
        val seq = writer.change(tx, roomId, type, id, actorId, now)
        table.update({ table.id eq id }) {
            it[this.seq] = seq
            it[updatedAt] = now
            block(it)
        }
    }

    /** 软删除（进回收站）。 */
    fun softDelete(tx: Tx, roomId: UUID, actorId: UUID, type: EntityType, id: UUID, table: SyncedTable, at: Instant = now()) =
        update(tx, roomId, actorId, type, id, table) {
            it[deletedAt] = at
            it[deletedBy] = actorId
        }

    /** 彻底删除（写 op = delete 的变化，客户端收到后删掉本地行）。 */
    fun hardDelete(tx: Tx, roomId: UUID, actorId: UUID, type: EntityType, id: UUID, table: SyncedTable) {
        writer.change(tx, roomId, type, id, actorId, now(), app.qichi.shared.model.ChangeOp.Delete)
        table.deleteWhere { table.id eq id }
    }

    /** 从回收站恢复。 */
    fun restore(tx: Tx, roomId: UUID, actorId: UUID, type: EntityType, id: UUID, table: SyncedTable) =
        update(tx, roomId, actorId, type, id, table) {
            it[deletedAt] = null
            it[deletedBy] = null
        }
}
