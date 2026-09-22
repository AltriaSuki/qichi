package app.qichi.core.sync

import app.qichi.core.database.EntityRow
import app.qichi.core.database.OutboxRow
import app.qichi.core.database.OutboxState
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.shared.api.Answer
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Event
import app.qichi.shared.api.Member
import app.qichi.shared.api.Message
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.Room
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.Todo
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.ZoneOffset
import java.util.UUID

/** 本机的一个实体：接口数据类 + 同步状态。 */
data class Local<out T : SyncEntity>(
    val value: T,
    val syncState: SyncState,
) {
    val isPending: Boolean get() = syncState == SyncState.PENDING
    val isFailed: Boolean get() = syncState == SyncState.FAILED
}

/**
 * 一个要发给服务端的写操作。
 * @param kind 响应怎么处理，见 [OutboxProcessor]；大多数是 [KIND_ENTITY]（响应体就是这个实体）
 */
data class OutboxOp(
    val method: HttpMethod,
    val path: String,
    val body: JsonElement? = null,
    val kind: String = KIND_ENTITY,
) {
    companion object {
        const val KIND_ENTITY = "entity"
        const val KIND_TODO_COMPLETE = "todo.complete"
        const val KIND_READ_MARKER = "read_marker"
        const val KIND_NO_CONTENT = "no_content"

        /** 响应是一条 Change（如从回收站恢复） */
        const val KIND_CHANGE = "change"

        inline fun <reified B> post(path: String, body: B, kind: String = KIND_ENTITY) =
            OutboxOp(HttpMethod.Post, path, QichiJson.encodeToJsonElement(body), kind)

        inline fun <reified B> patch(path: String, body: B) =
            OutboxOp(HttpMethod.Patch, path, QichiJson.encodeToJsonElement(body))

        inline fun <reified B> put(path: String, body: B, kind: String = KIND_ENTITY) =
            OutboxOp(HttpMethod.Put, path, QichiJson.encodeToJsonElement(body), kind)

        fun delete(path: String, kind: String = KIND_ENTITY) = OutboxOp(HttpMethod.Delete, path, kind = kind)

        fun action(path: String, kind: String = KIND_ENTITY) = OutboxOp(HttpMethod.Post, path, kind = kind)
    }
}

/**
 * 本机数据的读写规则（docs/05-sync-offline.md §3）。SyncEngine、OutboxProcessor、各功能的 Repository 都通过它。
 *
 * 核心规则：
 * - 服务端来的数据只覆盖 SYNCED 的行；PENDING / FAILED / CONFLICT 的行只更新 serverJson 和 seq，
 *   保留界面上看到的本机内容（本机修改在发出前、或用户处理冲突前绝不丢）。
 * - 本机写 = 同一个事务里改本地行（PENDING）+ 插入发件箱。
 */
class LocalStore(
    private val db: QichiDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entities get() = db.entities()
    private val outbox get() = db.outbox()

    // ── 服务端数据进来 ──

    /** 应用服务端的一个实体（同步、bootstrap）。 */
    suspend fun applyServer(entity: SyncEntity) {
        val type = typeOf(entity)
        val serverJson = encode(type, entity)
        val existing = entities.get(type.wireName, entity.id.toString())
        val row = when {
            existing == null || existing.syncState == SyncState.SYNCED ->
                toRow(entity, SyncState.SYNCED, serverJson, existing?.localTime ?: now())
            else -> existing.copy(seq = entity.seq, serverJson = serverJson)
        }
        entities.upsert(row)
    }

    /** 本机先删掉一行（彻底删除），操作另外入发件箱。 */
    suspend fun deleteLocal(type: EntityType, id: UUID) {
        entities.delete(type.wireName, id.toString())
    }

    /** 服务端彻底删除了这个实体。 */
    suspend fun applyServerDelete(type: EntityType, id: UUID) {
        entities.delete(type.wireName, id.toString())
    }

    /**
     * 发件箱里的一个操作成功了，响应体是实体的最新状态。
     * 如果这个实体还有别的操作在排队，界面内容保持本机的（只更新 serverJson）。
     */
    suspend fun applyResponse(entity: SyncEntity) {
        val type = typeOf(entity)
        val serverJson = encode(type, entity)
        val existing = entities.get(type.wireName, entity.id.toString())
        val stillPending = outbox.pendingCountFor(type.wireName, entity.id.toString()) > 0
        val row = if (existing != null && stillPending) {
            existing.copy(seq = entity.seq, serverJson = serverJson)
        } else {
            toRow(entity, SyncState.SYNCED, serverJson, existing?.localTime ?: now())
        }
        entities.upsert(row)
    }

    /**
     * 推进未读位置的响应：存服务端的那一行，并删掉本机先建的临时行
     * （第一次推进时本机不知道服务端会用哪个 id）。
     */
    suspend fun applyReadMarker(marker: ReadMarker) = db.transaction {
        applyResponse(marker)
        entities.readMarkers(marker.roomId.toString(), marker.userId.toString())
            .filter { it.id != marker.id.toString() }
            .forEach { entities.delete(it.type, it.id) }
    }

    // ── 本机写 ──

    /**
     * 本机写一个实体并把操作放进发件箱（同一个事务）。调用方随后要唤起发件箱（SyncScheduler.kickOutbox）。
     * @param entity 本机乐观更新后的实体
     * @param coalesce 为 true 时，同一房间同一种 kind 的待发操作只保留这一条（如推进已读位置）
     */
    suspend fun writeLocal(roomId: UUID, entity: SyncEntity, op: OutboxOp, coalesce: Boolean = false) {
        db.transaction {
            val type = typeOf(entity)
            val existing = entities.get(type.wireName, entity.id.toString())
            val state = if (existing?.syncState == SyncState.CONFLICT) SyncState.CONFLICT else SyncState.PENDING
            entities.upsert(
                toRow(entity, state, existing?.serverJson, existing?.localTime ?: now()).copy(seq = existing?.seq),
            )
            if (coalesce) outbox.deletePendingOfKind(roomId.toString(), op.kind)
            enqueue(roomId, type, entity.id, op)
        }
    }

    /**
     * 乐观地改本地显示内容，但不入发件箱、同步状态不变（通常仍是 SYNCED）：
     * 用于服务端会连带完成的变化（如删除父待办时的子任务），之后的拉取会用服务端状态覆盖、自行纠正。
     */
    suspend fun applyOptimistic(entity: SyncEntity) {
        val type = typeOf(entity)
        val existing = entities.get(type.wireName, entity.id.toString()) ?: return
        entities.upsert(
            toRow(entity, existing.syncState, existing.serverJson, existing.localTime).copy(seq = existing.seq),
        )
    }

    /** 只放进发件箱、不改本地行（例如本地行已经由别的方式更新）。 */
    suspend fun enqueue(roomId: UUID, type: EntityType, entityId: UUID, op: OutboxOp) {
        outbox.insert(
            OutboxRow(
                roomId = roomId.toString(),
                entityType = type.wireName,
                entityId = entityId.toString(),
                kind = op.kind,
                method = op.method.value,
                path = op.path,
                bodyJson = op.body?.let { QichiJson.encodeToString(JsonElement.serializer(), it) },
                createdAt = now(),
            ),
        )
    }

    /** 重试发送失败的实体：把它的失败操作放回队列。 */
    suspend fun retry(type: EntityType, id: UUID) = db.transaction {
        outbox.retryAllFor(type.wireName, id.toString())
        entities.setState(type.wireName, id.toString(), SyncState.PENDING)
    }

    /**
     * 放弃发送失败的修改：删掉它的发件箱操作；服务端有这个实体就恢复成服务端的内容，没有就删掉本地行。
     */
    suspend fun abandon(type: EntityType, id: UUID) = db.transaction {
        outbox.deleteAllFor(type.wireName, id.toString())
        val row = entities.get(type.wireName, id.toString()) ?: return@transaction
        val server = row.serverJson
        if (server == null) {
            entities.delete(type.wireName, id.toString())
        } else {
            val entity = decode(type, server)
            entities.upsert(toRow(entity, SyncState.SYNCED, server, row.localTime))
        }
    }

    suspend fun markFailed(type: String, id: String, error: String?) = db.transaction {
        outbox.failAllFor(type, id, error)
        entities.setState(type, id, SyncState.FAILED)
    }

    suspend fun markConflict(type: String, id: String) {
        entities.setState(type, id, SyncState.CONFLICT)
    }

    // ── 读 ──

    suspend fun <T : SyncEntity> get(type: EntityType, id: UUID): Local<T>? =
        entities.get(type.wireName, id.toString())?.let { toLocal(it) }

    companion object {
        fun typeOf(entity: SyncEntity): EntityType = when (entity) {
            is Room -> EntityType.Room
            is Member -> EntityType.Member
            is Message -> EntityType.Message
            is ReadMarker -> EntityType.ReadMarker
            is Mood -> EntityType.Mood
            is MoodReply -> EntityType.MoodResponse
            is Todo -> EntityType.Todo
            is Event -> EntityType.Event
            is Question -> EntityType.Question
            is QnaRound -> EntityType.QnaRound
            is Answer -> EntityType.Answer
            is Plan -> EntityType.Plan
            is PlanStage -> EntityType.PlanStage
            is Milestone -> EntityType.Milestone
            is PlanLog -> EntityType.PlanLog
        }

        fun encode(type: EntityType, entity: SyncEntity): String =
            QichiJson.encodeToString(JsonElement.serializer(), EntityCodec.encode(type, entity))

        fun decode(type: EntityType, json: String): SyncEntity =
            EntityCodec.decode(type, QichiJson.parseToJsonElement(json)) as SyncEntity

        @Suppress("UNCHECKED_CAST")
        fun <T : SyncEntity> toLocal(row: EntityRow): Local<T> =
            Local(decode(fromWire(row.type), row.json) as T, row.syncState)

        /** 由实体计算出索引列。 */
        fun toRow(entity: SyncEntity, state: SyncState, serverJson: String?, localTime: Long): EntityRow {
            val type = typeOf(entity)
            val json = encode(type, entity)
            fun base(roomId: UUID, deleted: Boolean, owner: UUID?, parent: UUID?, sortSeq: Long?, sortTime: Long?) =
                EntityRow(
                    type = type.wireName,
                    id = entity.id.toString(),
                    roomId = roomId.toString(),
                    seq = if (serverJson == null) null else entity.seq,
                    syncState = state,
                    deleted = deleted,
                    ownerId = owner?.toString(),
                    parentId = parent?.toString(),
                    sortSeq = sortSeq,
                    sortTime = sortTime,
                    localTime = localTime,
                    json = json,
                    serverJson = serverJson,
                )
            return when (entity) {
                is Room -> base(entity.id, false, entity.createdBy, null, null, entity.createdAt.toEpochMilli())
                is Member -> base(entity.roomId, entity.deletedAt != null, entity.userId, null, null, entity.joinedAt.toEpochMilli())
                is Message -> base(
                    entity.roomId, entity.deletedAt != null, entity.authorId, entity.replyToId,
                    // 还没发出去的消息没有 createdSeq（本机用 0 占位）
                    entity.createdSeq.takeIf { it > 0 }, entity.createdAt.toEpochMilli(),
                )
                is ReadMarker -> base(entity.roomId, false, entity.userId, null, null, null)
                is Mood -> base(entity.roomId, entity.deletedAt != null, entity.authorId, null, null, entity.createdAt.toEpochMilli())
                is MoodReply -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.moodId, null, entity.createdAt.toEpochMilli())
                is Todo -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, entity.parentId, null, entity.createdAt.toEpochMilli())
                is Event -> base(
                    entity.roomId, entity.deletedAt != null, entity.createdBy, null, null,
                    entity.startsAt?.toEpochMilli() ?: entity.startDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
                )
                is Question -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, null, null, entity.createdAt.toEpochMilli())
                is QnaRound -> base(entity.roomId, entity.deletedAt != null, null, entity.questionId, null, entity.createdAt.toEpochMilli())
                is Answer -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.roundId, null, entity.createdAt.toEpochMilli())
                is Plan -> base(entity.roomId, entity.deletedAt != null, entity.ownerId, null, null, entity.createdAt.toEpochMilli())
                is PlanStage -> base(entity.roomId, entity.deletedAt != null, null, entity.planId, null, entity.createdAt.toEpochMilli())
                is Milestone -> base(entity.roomId, entity.deletedAt != null, null, entity.planId, null,
                    entity.targetDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli())
                is PlanLog -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.planId, null, entity.createdAt.toEpochMilli())
            }
        }
    }
}
