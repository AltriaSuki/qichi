package app.qichi.server.sync

import app.qichi.server.db.ChangeLog
import app.qichi.server.db.Events
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Answers
import app.qichi.server.db.Questions
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.ReadMarkers
import app.qichi.server.db.Messages
import app.qichi.server.db.Milestones
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Ideas
import app.qichi.server.db.Plans
import app.qichi.server.db.RoomMembers
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.events.toEvent
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.messages.toReadMarker
import app.qichi.server.moods.toMood
import app.qichi.server.moods.toMoodReply
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.ideas.toIdea
import app.qichi.server.plans.toMilestone
import app.qichi.server.plans.toPlan
import app.qichi.server.plans.toPlanLog
import app.qichi.server.plans.toPlanStage
import app.qichi.server.qna.toAnswer
import app.qichi.server.qna.toQuestion
import app.qichi.server.qna.toQnaRound
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomRepository.toMember
import app.qichi.server.todos.toTodo
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.Change
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.fromWire
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection
import java.util.UUID

/**
 * 首次快照与增量同步（docs/05-sync-offline.md §2.2）。
 * 对方的 read_marker 永远不下发（不做已读回执）。
 */
class SyncService(private val db: QichiDatabase) {

    /** 同一个一致性快照里返回房间的当前状态；之后用 sync?since=lastSeq 增量拉取。 */
    suspend fun bootstrap(userId: UUID, roomId: UUID): Bootstrap =
        db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            if (!RoomRepository.isMember(roomId, userId)) notFound()
            val messages = messageQuery()
                .where { Messages.roomId eq roomId }
                .orderBy(Messages.createdSeq, SortOrder.DESC)
                .limit(Limits.BOOTSTRAP_MESSAGES + 1)
                .map { it.toMessage() }
            Bootstrap(
                room = RoomRepository.room(roomId)!!,
                members = RoomRepository.allMembers(roomId),
                lastSeq = RoomRepository.lastSeq(roomId),
                readMarker = ReadMarkers.selectAll()
                    .where { (ReadMarkers.roomId eq roomId) and (ReadMarkers.userId eq userId) }
                    .singleOrNull()?.toReadMarker(),
                moods = Moods.selectAll().where { Moods.roomId eq roomId }.orderBy(Moods.seq).map { it.toMood() },
                moodReplies = MoodResponses.selectAll().where { MoodResponses.roomId eq roomId }
                    .orderBy(MoodResponses.seq).map { it.toMoodReply() },
                todos = Todos.selectAll().where { Todos.roomId eq roomId }.orderBy(Todos.seq).map { it.toTodo() },
                events = Events.selectAll().where { Events.roomId eq roomId }.orderBy(Events.seq).map { it.toEvent() },
                messages = messages.take(Limits.BOOTSTRAP_MESSAGES),
                hasMoreMessages = messages.size > Limits.BOOTSTRAP_MESSAGES,
                questions = Questions.selectAll().where { Questions.roomId eq roomId }.map { it.toQuestion() },
                qnaRounds = QnaRounds.selectAll().where { QnaRounds.roomId eq roomId }.map { it.toQnaRound() },
                answers = Answers.selectAll().where { Answers.roomId eq roomId }.map { it.toAnswer() }
                    .filter { answer -> answer.authorId == userId || QnaRounds.selectAll().where { QnaRounds.id eq answer.roundId }.single()[QnaRounds.revealedAt] != null },
                plans = Plans.selectAll().where { Plans.roomId eq roomId }.map { it.toPlan() },
                planStages = PlanStages.selectAll().where { PlanStages.roomId eq roomId }.map { it.toPlanStage() },
                milestones = Milestones.selectAll().where { Milestones.roomId eq roomId }.map { it.toMilestone() },
                planLogs = PlanLogs.selectAll().where { PlanLogs.roomId eq roomId }.map { it.toPlanLog() },
                ideas = Ideas.selectAll().where { Ideas.roomId eq roomId }.map { it.toIdea() },
            )
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

            val changes = latest.sortedBy { it.seq }.mapNotNull { row ->
                val entity = entities[row.type to row.id]
                when {
                    // 对方的未读位置不下发
                    row.type == EntityType.ReadMarker && entity is app.qichi.shared.api.ReadMarker && entity.userId != userId -> null
                    row.type == EntityType.Answer && entity is app.qichi.shared.api.Answer && entity.authorId != userId &&
                        QnaRounds.selectAll().where { QnaRounds.id eq entity.roundId }.singleOrNull()?.get(QnaRounds.revealedAt) == null -> null
                    row.op == ChangeOp.Delete || entity == null -> Change(row.seq, row.type, row.id, ChangeOp.Delete, null)
                    else -> Change(row.seq, row.type, row.id, ChangeOp.Upsert, EntityCodec.encode(row.type, entity))
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
            val ids = group.map { it.id }
            val loaded: List<SyncEntity> = when (type) {
                EntityType.Room -> ids.mapNotNull { RoomRepository.room(it) }
                EntityType.Member -> RoomMembers.join(app.qichi.server.db.Users, JoinType.INNER, RoomMembers.userId, app.qichi.server.db.Users.id)
                    .selectAll().where { RoomMembers.id inList ids }.map { it.toMember() }
                EntityType.Message -> messageQuery().where { Messages.id inList ids }.map { it.toMessage() }
                EntityType.ReadMarker -> ReadMarkers.selectAll().where { ReadMarkers.id inList ids }.map { it.toReadMarker() }
                EntityType.Mood -> Moods.selectAll().where { Moods.id inList ids }.map { it.toMood() }
                EntityType.MoodResponse -> MoodResponses.selectAll().where { MoodResponses.id inList ids }.map { it.toMoodReply() }
                EntityType.Todo -> Todos.selectAll().where { Todos.id inList ids }.map { it.toTodo() }
                EntityType.Event -> Events.selectAll().where { Events.id inList ids }.map { it.toEvent() }
                EntityType.Question -> Questions.selectAll().where { Questions.id inList ids }.map { it.toQuestion() }
                EntityType.QnaRound -> QnaRounds.selectAll().where { QnaRounds.id inList ids }.map { it.toQnaRound() }
                EntityType.Answer -> Answers.selectAll().where { Answers.id inList ids }.map { it.toAnswer() }
                EntityType.Plan -> Plans.selectAll().where { Plans.id inList ids }.map { it.toPlan() }
                EntityType.Idea -> Ideas.selectAll().where { Ideas.id inList ids }.map { it.toIdea() }
                EntityType.PlanStage -> PlanStages.selectAll().where { PlanStages.id inList ids }.map { it.toPlanStage() }
                EntityType.Milestone -> Milestones.selectAll().where { Milestones.id inList ids }.map { it.toMilestone() }
                EntityType.PlanLog -> PlanLogs.selectAll().where { PlanLogs.id inList ids }.map { it.toPlanLog() }
            }
            loaded.forEach { result[type to it.id] = it }
        }
        return result
    }
}
