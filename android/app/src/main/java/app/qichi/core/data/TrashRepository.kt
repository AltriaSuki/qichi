package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Event
import app.qichi.shared.api.Message
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.Book
import app.qichi.shared.api.Decision
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.Summary
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Document
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.Question
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.Mood
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.Todo
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.TrashType
import app.qichi.shared.model.wireName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import app.qichi.core.sync.offMain

/** 回收站里的一项。 */
data class TrashEntry(
    val type: TrashType,
    val entity: SyncEntity,
    val deletedAt: Instant,
    val deletedBy: UUID?,
) {
    val id: UUID get() = entity.id
}

/**
 * 回收站：从本机数据库读（离线也能看），恢复与彻底删除先改本机、再经发件箱发出。
 * 规则同服务端：随父待办一起删掉的子任务不单独列出；心情、计划的进展记录、批注、文稿留言只有作者能恢复或彻底删除，
 * 所以只列自己的。
 */
class TrashRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
) {
    private val me: UUID? get() = session.currentUserId

    fun observe(roomId: UUID): Flow<List<TrashEntry>> =
        db.entities().observeDeleted(roomId.toString(), TYPES.map { it.entityType.wireName }).map { rows ->
            val entities = rows.map { LocalStore.toLocal<SyncEntity>(it).value }
            val deletedTodos = entities.filterIsInstance<Todo>().map { it.id }.toSet()
            val deletedPlans = entities.filterIsInstance<Plan>().map { it.id }.toSet()
            val deletedReviews = entities.filterIsInstance<ReviewDocument>().map { it.id }.toSet()
            val deletedDocuments = entities.filterIsInstance<Document>().map { it.id }.toSet()
            entities.mapNotNull { entity ->
                when (entity) {
                    is Message -> TrashEntry(TrashType.Message, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Mood -> if (entity.authorId != me) null else TrashEntry(TrashType.Mood, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Todo -> if (entity.parentId in deletedTodos) null else TrashEntry(TrashType.Todo, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Event -> TrashEntry(TrashType.Event, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Question -> TrashEntry(TrashType.Question, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Plan -> TrashEntry(TrashType.Plan, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Idea -> TrashEntry(TrashType.Idea, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Document -> TrashEntry(TrashType.Document, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is BoardTopic -> TrashEntry(TrashType.BoardTopic, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is BoardPost -> TrashEntry(TrashType.BoardPost, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is ArchiveItem -> TrashEntry(TrashType.ArchiveItem, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Decision -> TrashEntry(TrashType.Decision, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Book -> TrashEntry(TrashType.Book, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Summary -> TrashEntry(TrashType.Summary, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    // 计划也在回收站里时，阶段、里程碑随计划一起，不单独列出
                    is PlanStage -> if (entity.planId in deletedPlans) null else TrashEntry(TrashType.PlanStage, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is Milestone -> if (entity.planId in deletedPlans) null else TrashEntry(TrashType.Milestone, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    // 进展记录（P14-03）：同上；只有记的人能恢复，只列自己的
                    is PlanLog -> if (entity.planId in deletedPlans || entity.authorId != me) null
                        else TrashEntry(TrashType.PlanLog, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is ReviewDocument -> TrashEntry(TrashType.ReviewDocument, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    // 批注、文稿留言（P15-03）：只有写的人能恢复，只列自己的；审稿文件、文稿也在回收站里时随它一起，不单独列
                    is Annotation -> if (entity.documentId in deletedReviews || entity.authorId != me) null
                        else TrashEntry(TrashType.Annotation, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    is DocComment -> if (entity.documentId in deletedDocuments || entity.authorId != me) null
                        else TrashEntry(TrashType.DocComment, entity, entity.deletedAt ?: return@mapNotNull null, entity.deletedBy)
                    else -> null
                }
            }.sortedByDescending { it.deletedAt }
        }.offMain()

    /**
     * 打开回收站时（在线）从服务端补齐：这台手机装好之前就删掉的旧消息，本机原本没有。
     * 取到的实体照常存进本机，列表仍然从本机读。
     */
    suspend fun refresh(roomId: UUID) {
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val after = cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val page = api.get<TrashPage>("rooms/$roomId/trash?limit=100$after")
            db.transaction {
                page.items.forEach { item -> store.applyServer(EntityCodec.decode(item.type.entityType, item.data) as SyncEntity) }
            }
            cursor = page.nextCursor ?: return
        }
    }

    /** 恢复：内容按删除前的样子回来（消息回到原来的位置）。 */
    suspend fun restore(roomId: UUID, entry: TrashEntry) {
        val restored: SyncEntity = when (val e = entry.entity) {
            is Message -> e.copy(deletedAt = null, deletedBy = null)
            is Mood -> e.copy(deletedAt = null, deletedBy = null)
            is Todo -> e.copy(deletedAt = null, deletedBy = null)
            is Event -> e.copy(deletedAt = null, deletedBy = null)
            is Question -> e.copy(deletedAt = null, deletedBy = null)
            is Plan -> e.copy(deletedAt = null, deletedBy = null)
            is Idea -> e.copy(deletedAt = null, deletedBy = null)
            is Document -> e.copy(deletedAt = null, deletedBy = null)
            is BoardTopic -> e.copy(deletedAt = null, deletedBy = null)
            is BoardPost -> e.copy(deletedAt = null, deletedBy = null)
            is ArchiveItem -> e.copy(deletedAt = null, deletedBy = null)
            is Decision -> e.copy(deletedAt = null, deletedBy = null)
            is Book -> e.copy(deletedAt = null, deletedBy = null)
            is Summary -> e.copy(deletedAt = null, deletedBy = null)
            is PlanStage -> e.copy(deletedAt = null, deletedBy = null)
            is Milestone -> e.copy(deletedAt = null, deletedBy = null)
            is PlanLog -> e.copy(deletedAt = null, deletedBy = null)
            is ReviewDocument -> e.copy(deletedAt = null, deletedBy = null)
            is Annotation -> e.copy(deletedAt = null, deletedBy = null)
            is DocComment -> e.copy(deletedAt = null, deletedBy = null)
            else -> return
        }
        db.transaction {
            store.writeLocal(roomId, restored, OutboxOp.action(path(roomId, entry) + "/restore", kind = OutboxOp.KIND_CHANGE))
            // 和父待办同时删掉的子任务一起回来（服务端也是这样做的）
            if (entry.entity is Todo) {
                childrenDeletedWith(roomId, entry.entity).forEach { store.applyOptimistic(it.copy(deletedAt = null, deletedBy = null)) }
            }
        }
        scheduler.kickOutbox()
    }

    /** 彻底删除：本机马上删掉（连同子任务、心情的回应、审稿文件的版本和批注、留言的回复），再告诉服务端。 */
    suspend fun purge(roomId: UUID, entry: TrashEntry) {
        db.transaction {
            val entity = entry.entity
            if (entity is Todo) {
                db.entities().children(roomId.toString(), EntityType.Todo.wireName, entity.id.toString())
                    .forEach { store.deleteLocal(EntityType.Todo, UUID.fromString(it.id)) }
            }
            if (entity is Mood) {
                db.entities().children(roomId.toString(), EntityType.MoodResponse.wireName, entity.id.toString())
                    .forEach { store.deleteLocal(EntityType.MoodResponse, UUID.fromString(it.id)) }
            }
            if (entity is Plan) {
                listOf(EntityType.PlanStage, EntityType.Milestone, EntityType.PlanLog).forEach { type ->
                    db.entities().children(roomId.toString(), type.wireName, entity.id.toString())
                        .forEach { store.deleteLocal(type, UUID.fromString(it.id)) }
                }
            }
            // 审稿文件（P15-03）：版本、AI 发现、批注连同批注下的讨论
            if (entity is ReviewDocument) {
                db.entities().children(roomId.toString(), EntityType.Annotation.wireName, entity.id.toString()).forEach { annotation ->
                    deleteChildren(roomId, EntityType.AnnotationReply, annotation.id)
                    store.deleteLocal(EntityType.Annotation, UUID.fromString(annotation.id))
                }
                deleteChildren(roomId, EntityType.ReviewVersion, entity.id.toString())
                deleteChildren(roomId, EntityType.AiFinding, entity.id.toString())
            }
            if (entity is Annotation) deleteChildren(roomId, EntityType.AnnotationReply, entity.id.toString())
            // 文稿留言挂在文稿下：讨论的回复是同一篇文稿下 parentId 指向它的那些；文稿删掉时留言都删
            if (entity is DocComment || entity is Document) {
                db.entities().children(roomId.toString(), EntityType.DocComment.wireName, (if (entity is DocComment) entity.documentId else entity.id).toString())
                    .map { LocalStore.toLocal<DocComment>(it).value }
                    .filter { entity is Document || it.parentId == entity.id }
                    .forEach { store.deleteLocal(EntityType.DocComment, it.id) }
            }
            store.deleteLocal(entry.type.entityType, entry.id)
            store.enqueue(roomId, entry.type.entityType, entry.id, OutboxOp.delete(path(roomId, entry), kind = OutboxOp.KIND_NO_CONTENT))
        }
        scheduler.kickOutbox()
    }

    private suspend fun deleteChildren(roomId: UUID, type: EntityType, parentId: String) {
        db.entities().children(roomId.toString(), type.wireName, parentId).forEach { store.deleteLocal(type, UUID.fromString(it.id)) }
    }

    private suspend fun childrenDeletedWith(roomId: UUID, parent: Todo): List<Todo> =
        db.entities().children(roomId.toString(), EntityType.Todo.wireName, parent.id.toString())
            .map { LocalStore.toLocal<Todo>(it).value }
            .filter { it.deletedAt != null && it.deletedAt == parent.deletedAt }

    private fun path(roomId: UUID, entry: TrashEntry) = "rooms/$roomId/trash/${entry.type.wireName}/${entry.id}"

    private companion object {
        val TYPES = TrashType.entries
        const val MAX_PAGES = 10
    }
}

val TrashType.entityType: EntityType
    get() = when (this) {
        TrashType.Message -> EntityType.Message
        TrashType.Mood -> EntityType.Mood
        TrashType.Todo -> EntityType.Todo
        TrashType.Event -> EntityType.Event
        TrashType.Question -> EntityType.Question
        TrashType.Plan -> EntityType.Plan
        TrashType.Idea -> EntityType.Idea
        TrashType.Document -> EntityType.Document
        TrashType.BoardTopic -> EntityType.BoardTopic
        TrashType.BoardPost -> EntityType.BoardPost
        TrashType.ArchiveItem -> EntityType.ArchiveItem
        TrashType.Decision -> EntityType.Decision
        TrashType.Book -> EntityType.Book
        TrashType.Summary -> EntityType.Summary
        TrashType.PlanStage -> EntityType.PlanStage
        TrashType.Milestone -> EntityType.Milestone
        TrashType.ReviewDocument -> EntityType.ReviewDocument
        TrashType.Annotation -> EntityType.Annotation
        TrashType.DocComment -> EntityType.DocComment
        TrashType.PlanLog -> EntityType.PlanLog
    }
