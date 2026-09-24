package app.qichi.core.sync

import app.qichi.core.database.EntityRow
import app.qichi.core.database.OutboxRow
import app.qichi.core.database.OutboxState
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.shared.api.Answer
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Event
import app.qichi.core.data.DraftStore
import app.qichi.core.database.DocumentVersionRow
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.Book
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Summary
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.BoardReaction
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Document
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.Idea
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

        /**
         * 保存文稿的新版本：响应是 DocumentVersion（不是同步实体）。成功时写进版本缓存、整理草稿；
         * 基线落后（409）或被拒绝时只丢掉这条操作，草稿原样留着，界面据此进入「重基线」。
         */
        const val KIND_DOC_VERSION = "document.version"

        /** AI 发现转成批注：响应是新建的批注；发现本身按本机状态标为已同步，之后的同步会带来服务端的版本 */
        const val KIND_FINDING_CONVERT = "ai_finding.convert"

        /** 保存阅读进度：服务端已有我的进度时返回的 id 可能和本机不同，本机多出来的那条删掉 */
        const val KIND_READING_PROGRESS = "reading_progress"

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

    /** 阅读进度的响应：存服务端那条，删掉本机同一本书、同一个人的其它进度行。 */
    suspend fun applyReadingProgress(progress: ReadingProgress) = db.transaction {
        applyResponse(progress)
        entities.byParent(EntityType.ReadingProgress.wireName, progress.bookId.toString())
            .filter { it.ownerId == progress.userId.toString() && it.id != progress.id.toString() }
            .forEach { entities.delete(it.type, it.id) }
    }

    /**
     * 文稿的新版本保存成功：存进版本缓存；草稿和刚保存的内容一样就删掉，
     * 保存之后又接着写了的，草稿改为基于这个新版本。
     */
    suspend fun applyDocumentVersion(roomId: UUID, v: DocumentVersion) = db.transaction {
        db.documentVersions().upsert(
            DocumentVersionRow(
                id = v.id.toString(), roomId = roomId.toString(), documentId = v.documentId.toString(), version = v.version,
                baseVersion = v.baseVersion, authorId = v.authorId.toString(), charCount = v.charCount,
                restoredFromVersion = v.restoredFromVersion, createdAt = v.createdAt.toEpochMilli(), body = v.body,
            ),
        )
        // 文稿的最新版本号先在本机跟上（稍后的拉取会用服务端的完整状态覆盖），编辑器不会闪回旧版本
        get<Document>(EntityType.Document, v.documentId)?.value?.takeIf { it.latestVersion < v.version }?.let {
            applyOptimistic(it.copy(latestVersion = v.version, latestAuthorId = v.authorId, charCount = v.charCount, updatedAt = v.createdAt))
        }
        val key = DraftStore.documentKey(v.documentId)
        val draft = db.drafts().get(roomId.toString(), key) ?: return@transaction
        if (draft.baseVersion != v.baseVersion) return@transaction
        if (draft.text == v.body) {
            db.drafts().delete(roomId.toString(), key)
        } else {
            db.drafts().upsert(draft.copy(baseVersion = v.version))
        }
    }

    /** 操作成功但响应不是这个实体（例如 AI 发现转批注）：没有别的待发操作时标为已同步。 */
    suspend fun markSynced(type: String, id: String) {
        if (outbox.pendingCountFor(type, id) == 0) entities.setState(type, id, SyncState.SYNCED)
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
            is Idea -> EntityType.Idea
            is Document -> EntityType.Document
            is BoardTopic -> EntityType.BoardTopic
            is BoardPost -> EntityType.BoardPost
            is BoardReaction -> EntityType.BoardReaction
            is ArchiveItem -> EntityType.ArchiveItem
            is Decision -> EntityType.Decision
            is Book -> EntityType.Book
            is ReadingProgress -> EntityType.ReadingProgress
            is Highlight -> EntityType.Highlight
            is Summary -> EntityType.Summary
            is ReviewDocument -> EntityType.ReviewDocument
            is ReviewVersion -> EntityType.ReviewVersion
            is Annotation -> EntityType.Annotation
            is AnnotationReply -> EntityType.AnnotationReply
            is AiFinding -> EntityType.AiFinding
            is AiAction -> EntityType.AiAction
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
                is Idea -> base(entity.roomId, entity.deletedAt != null, entity.authorId, null, null, entity.createdAt.toEpochMilli())
                // 文稿列表按最近更新排序
                is Document -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, null, null, entity.updatedAt.toEpochMilli())
                is BoardTopic -> base(entity.roomId, entity.deletedAt != null, entity.authorId, null, null, entity.createdAt.toEpochMilli())
                is BoardPost -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.topicId, null, entity.createdAt.toEpochMilli())
                is BoardReaction -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.postId, null, entity.createdAt.toEpochMilli())
                is ArchiveItem -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, entity.sourceMessageId, null, entity.updatedAt.toEpochMilli())
                is Decision -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, null, null, entity.createdAt.toEpochMilli())
                is Book -> base(entity.roomId, entity.deletedAt != null, entity.addedBy, null, null, entity.createdAt.toEpochMilli())
                is ReadingProgress -> base(entity.roomId, entity.deletedAt != null, entity.userId, entity.bookId, null, entity.updatedAt.toEpochMilli())
                is Highlight -> base(entity.roomId, entity.deletedAt != null, entity.userId, entity.bookId, null, entity.createdAt.toEpochMilli())
                is Summary -> base(entity.roomId, entity.deletedAt != null, entity.requestedBy, null, null, entity.createdAt.toEpochMilli())
                // 审稿列表按最近更新排序；版本、批注挂在审稿文件下，讨论挂在批注下
                is ReviewDocument -> base(entity.roomId, entity.deletedAt != null, entity.createdBy, null, null, entity.updatedAt.toEpochMilli())
                is ReviewVersion -> base(entity.roomId, entity.deletedAt != null, entity.uploadedBy, entity.documentId, entity.version.toLong(), entity.createdAt.toEpochMilli())
                is Annotation -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.documentId, null, entity.createdAt.toEpochMilli())
                is AnnotationReply -> base(entity.roomId, entity.deletedAt != null, entity.authorId, entity.annotationId, null, entity.createdAt.toEpochMilli())
                is AiFinding -> base(entity.roomId, entity.deletedAt != null, entity.requestedBy, entity.documentId, null, entity.createdAt.toEpochMilli())
                // AI 提议挂在那条 AI 回答下，按在回答里的顺序
                is AiAction -> base(entity.roomId, entity.deletedAt != null, entity.requestedBy, entity.messageId, entity.position.toLong(), entity.createdAt.toEpochMilli())
            }
        }
    }
}
