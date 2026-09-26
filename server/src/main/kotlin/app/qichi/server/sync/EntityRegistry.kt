package app.qichi.server.sync

import app.qichi.server.ai.toAiAction
import app.qichi.server.archive.toArchiveItem
import app.qichi.server.board.toBoardPost
import app.qichi.server.board.toBoardReaction
import app.qichi.server.board.toBoardTopic
import app.qichi.server.db.AiActions
import app.qichi.server.db.AiFindings
import app.qichi.server.db.AnnotationReplies
import app.qichi.server.db.Annotations
import app.qichi.server.db.Answers
import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.BoardPosts
import app.qichi.server.db.BoardReactions
import app.qichi.server.db.BoardTopics
import app.qichi.server.db.Books
import app.qichi.server.db.Decisions
import app.qichi.server.db.DocComments
import app.qichi.server.db.Documents
import app.qichi.server.db.Events
import app.qichi.server.db.Highlights
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Milestones
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.server.db.ReadMarkers
import app.qichi.server.db.ReadingProgressTable
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.ReviewVersions
import app.qichi.server.db.RoomMembers
import app.qichi.server.db.Summaries
import app.qichi.server.db.SyncedTable
import app.qichi.server.db.Todos
import app.qichi.server.db.Users
import app.qichi.server.decisions.toDecision
import app.qichi.server.documents.toDocComment
import app.qichi.server.documents.toDocument
import app.qichi.server.events.toEvent
import app.qichi.server.ideas.toIdea
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.messages.toReadMarker
import app.qichi.server.moods.toMood
import app.qichi.server.moods.toMoodReply
import app.qichi.server.plans.toMilestone
import app.qichi.server.plans.toPlan
import app.qichi.server.plans.toPlanLog
import app.qichi.server.plans.toPlanStage
import app.qichi.server.qna.toAnswer
import app.qichi.server.qna.toQnaRound
import app.qichi.server.qna.toQuestion
import app.qichi.server.reading.bookQuery
import app.qichi.server.reading.toBook
import app.qichi.server.reading.toHighlight
import app.qichi.server.reading.toReadingProgress
import app.qichi.server.review.reviewVersionQuery
import app.qichi.server.review.toAiFinding
import app.qichi.server.review.toAnnotation
import app.qichi.server.review.toAnnotationReply
import app.qichi.server.review.toReviewDocument
import app.qichi.server.review.toReviewVersion
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomRepository.toMember
import app.qichi.server.summaries.toSummary
import app.qichi.server.todos.toTodo
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.model.EntityType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * 每种同步实体怎么读（P13-13）：增量同步按 id 批量读，首次快照读一个房间里的全部并放进 Bootstrap 的哪个字段。
 * 同步、快照、回收站都从这里取，新加一种同步实体在这里登记一行；漏了 EntityRegistryTest 直接失败。
 * 谁能看见哪些，另由 [Visibility] 判断。
 */
object EntityRegistry {

    /**
     * @param byIds 按 id 读当前状态（含已软删除的）
     * @param snapshotField 放进 Bootstrap 的哪个字段；为空的不按列表进快照（房间、成员、消息、已读位置在快照里另有专门的字段）
     * @param inRoom 一个房间里的全部（含已软删除的），快照用
     */
    class Entry(
        val type: EntityType,
        val byIds: (Collection<UUID>) -> List<SyncEntity>,
        val snapshotField: String? = null,
        val inRoom: (UUID) -> List<SyncEntity> = { emptyList() },
    )

    /** 普通的同步实体：一张表、一个映射，快照按 seq 排好 */
    private fun table(
        type: EntityType,
        table: SyncedTable,
        snapshotField: String,
        query: () -> Query = { table.selectAll() },
        map: (ResultRow) -> SyncEntity,
    ) = Entry(
        type,
        byIds = { ids -> query().where { table.id inList ids }.map(map) },
        snapshotField = snapshotField,
        inRoom = { roomId -> query().where { table.roomId eq roomId }.orderBy(table.seq).map(map) },
    )

    private val entries: Map<EntityType, Entry> = listOf(
        Entry(EntityType.Room, { ids -> ids.mapNotNull { RoomRepository.room(it) } }),
        Entry(EntityType.Member, { ids ->
            RoomMembers.join(Users, JoinType.INNER, RoomMembers.userId, Users.id).selectAll()
                .where { RoomMembers.id inList ids }.map { it.toMember() }
        }),
        Entry(EntityType.Message, { ids -> messageQuery().where { Messages.id inList ids }.map { it.toMessage() } }),
        Entry(EntityType.ReadMarker, { ids -> ReadMarkers.selectAll().where { ReadMarkers.id inList ids }.map { it.toReadMarker() } }),
        table(EntityType.Mood, Moods, "moods") { it.toMood() },
        table(EntityType.MoodResponse, MoodResponses, "moodReplies") { it.toMoodReply() },
        table(EntityType.Todo, Todos, "todos") { it.toTodo() },
        table(EntityType.Event, Events, "events") { it.toEvent() },
        table(EntityType.Question, Questions, "questions") { it.toQuestion() },
        table(EntityType.QnaRound, QnaRounds, "qnaRounds") { it.toQnaRound() },
        table(EntityType.Answer, Answers, "answers") { it.toAnswer() },
        table(EntityType.Plan, Plans, "plans") { it.toPlan() },
        table(EntityType.PlanStage, PlanStages, "planStages") { it.toPlanStage() },
        table(EntityType.Milestone, Milestones, "milestones") { it.toMilestone() },
        table(EntityType.PlanLog, PlanLogs, "planLogs") { it.toPlanLog() },
        table(EntityType.Idea, Ideas, "ideas") { it.toIdea() },
        table(EntityType.Document, Documents, "documents") { it.toDocument() },
        table(EntityType.BoardTopic, BoardTopics, "boardTopics") { it.toBoardTopic() },
        table(EntityType.BoardPost, BoardPosts, "boardPosts") { it.toBoardPost() },
        table(EntityType.BoardReaction, BoardReactions, "boardReactions") { it.toBoardReaction() },
        table(EntityType.ArchiveItem, ArchiveItems, "archiveItems") { it.toArchiveItem() },
        table(EntityType.Decision, Decisions, "decisions") { it.toDecision() },
        table(EntityType.Book, Books, "books", query = { bookQuery() }) { it.toBook() },
        table(EntityType.ReadingProgress, ReadingProgressTable, "readingProgress") { it.toReadingProgress() },
        table(EntityType.Highlight, Highlights, "highlights") { it.toHighlight() },
        table(EntityType.Summary, Summaries, "summaries") { it.toSummary() },
        table(EntityType.ReviewDocument, ReviewDocuments, "reviewDocuments") { it.toReviewDocument() },
        table(EntityType.ReviewVersion, ReviewVersions, "reviewVersions", query = { reviewVersionQuery() }) { it.toReviewVersion() },
        table(EntityType.Annotation, Annotations, "annotations") { it.toAnnotation() },
        table(EntityType.AnnotationReply, AnnotationReplies, "annotationReplies") { it.toAnnotationReply() },
        table(EntityType.AiFinding, AiFindings, "aiFindings") { it.toAiFinding() },
        table(EntityType.AiAction, AiActions, "aiActions") { it.toAiAction() },
        table(EntityType.DocComment, DocComments, "docComments") { it.toDocComment() },
    ).associateBy { it.type }

    val types: Set<EntityType> get() = entries.keys

    fun entry(type: EntityType): Entry = entries[type] ?: error("同步实体 $type 没有登记读法（EntityRegistry）")

    /** 进快照的，按登记的顺序 */
    val snapshots: List<Entry> get() = entries.values.filter { it.snapshotField != null }

    /** 按 id 读一批同一种实体的当前状态（含已软删除的）；不存在的不返回 */
    fun load(type: EntityType, ids: Collection<UUID>): List<SyncEntity> = if (ids.isEmpty()) emptyList() else entry(type).byIds(ids)
}
