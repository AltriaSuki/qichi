package app.qichi.server.sync

import app.qichi.server.db.ChangeLog
import app.qichi.server.db.Messages
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.ReadMarkers
import app.qichi.server.db.tx
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.messages.toReadMarker
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.shared.api.Change
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Member
import app.qichi.shared.api.Message
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.Room
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.fromWire
import app.qichi.shared.rules.Limits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection
import java.util.UUID

/**
 * 首次快照与增量同步（docs/05-sync-offline.md §2.2）。
 * 每种实体怎么读由 [EntityRegistry] 登记（P13-13），谁能看见什么（对方没揭晓的回答、没共享的标记、对方的已读位置）
 * 统一由 [Visibility] 判断，这里不再逐种实体各写一遍。
 */
class SyncService(private val db: QichiDatabase) {

    /**
     * 同一个一致性快照里返回房间的当前状态（形状见 shared 的 Bootstrap）；之后用 sync?since=lastSeq 增量拉取。
     * 房间、成员、最近的消息、自己的已读位置单独给，其余每种实体按登记表一个字段一个列表。
     */
    suspend fun bootstrap(userId: UUID, roomId: UUID): JsonObject =
        db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            if (!RoomRepository.isMember(roomId, userId)) notFound()
            val messages = messageQuery()
                .where { Messages.roomId eq roomId }
                .orderBy(Messages.createdSeq, SortOrder.DESC)
                .limit(Limits.BOOTSTRAP_MESSAGES + 1)
                .map { it.toMessage() }
            val readMarker = ReadMarkers.selectAll().where { ReadMarkers.roomId eq roomId }.map { it.toReadMarker() }
                .singleOrNull { Visibility.readMarker(it, userId) }
            buildJsonObject {
                put("room", QichiJson.encodeToJsonElement(Room.serializer(), RoomRepository.room(roomId)!!))
                put("members", QichiJson.encodeToJsonElement(ListSerializer(Member.serializer()), RoomRepository.allMembers(roomId)))
                put("lastSeq", RoomRepository.lastSeq(roomId))
                put("readMarker", QichiJson.encodeToJsonElement(ReadMarker.serializer().nullable, readMarker))
                put("messages", QichiJson.encodeToJsonElement(ListSerializer(Message.serializer()), messages.take(Limits.BOOTSTRAP_MESSAGES)))
                put("hasMoreMessages", messages.size > Limits.BOOTSTRAP_MESSAGES)
                for (entry in EntityRegistry.snapshots) {
                    val all = entry.inRoom(roomId)
                    val delivery = Visibility.deliveryFor(all, userId)
                    // 快照里只有「原样下发」的：对方没揭晓的回答、没共享的标记都不出现
                    val visible = all.filter { delivery(it) == Visibility.Delivery.Show }
                    put(entry.snapshotField!!, JsonArray(visible.map { EntityCodec.encode(entry.type, it) }))
                }
            }
        }

    /**
     * 返回 seq > [since] 的变化，按 seq 升序，最多 [limit] 条变化记录。
     * 同一实体在本页内多次变化只返回最后一次；data 是实体的完整当前状态。
     */
    suspend fun sync(userId: UUID, roomId: UUID, since: Long, limit: Int): SyncResponse {
        validate {
            check(since >= 0, "since", "不能小于 0")
            check(limit in 1..Limits.SYNC_PAGE_MAX, "limit", "1–${Limits.SYNC_PAGE_MAX}")
        }
        return db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            if (!RoomRepository.isMember(roomId, userId)) notFound()
            val rows = ChangeLog.selectAll()
                .where { (ChangeLog.roomId eq roomId) and (ChangeLog.seq greater since) }
                .orderBy(ChangeLog.seq)
                .limit(limit + 1)
                .map { ChangeRow(it[ChangeLog.seq], fromWire(it[ChangeLog.entityType]), it[ChangeLog.entityId], fromWire(it[ChangeLog.op])) }
            val page = rows.take(limit)
            val toSeq = page.lastOrNull()?.seq ?: since

            // 同一实体只保留本页里最后一次变化
            val latest = page.groupBy { it.type to it.id }.values.map { changes -> changes.maxBy { it.seq } }
            val entities = loadEntities(latest.filter { it.op == ChangeOp.Upsert })
            val delivery = Visibility.deliveryFor(entities.values, userId)

            val changes = latest.sortedBy { it.seq }.mapNotNull { row ->
                val entity = entities[row.type to row.id]
                val deleted = Change(row.seq, row.type, row.id, ChangeOp.Delete, null)
                when {
                    row.op == ChangeOp.Delete || entity == null -> deleted
                    else -> when (delivery(entity)) {
                        Visibility.Delivery.Show -> Change(row.seq, row.type, row.id, ChangeOp.Upsert, EntityCodec.encode(row.type, entity))
                        Visibility.Delivery.AsDeleted -> deleted
                        Visibility.Delivery.Skip -> null
                    }
                }
            }
            SyncResponse(fromSeq = since, toSeq = toSeq, hasMore = rows.size > limit, changes = changes)
        }
    }

    private data class ChangeRow(val seq: Long, val type: EntityType, val id: UUID, val op: ChangeOp)

    /** 按类型批量读取实体的当前状态（含已软删除的）。 */
    private fun loadEntities(rows: List<ChangeRow>): Map<Pair<EntityType, UUID>, SyncEntity> {
        val result = HashMap<Pair<EntityType, UUID>, SyncEntity>()
        for ((type, group) in rows.groupBy { it.type }) {
            EntityRegistry.load(type, group.map { it.id }).forEach { result[type to it.id] = it }
        }
        return result
    }
}
