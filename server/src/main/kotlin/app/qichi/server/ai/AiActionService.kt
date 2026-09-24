package app.qichi.server.ai

import app.qichi.server.archive.ArchiveService
import app.qichi.server.db.AiActions
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.events.EventService
import app.qichi.server.ideas.IdeaService
import app.qichi.server.plugins.notFound
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.server.todos.TodoService
import app.qichi.shared.api.AcceptAiActionRequest
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toAiAction() = AiAction(
    id = this[AiActions.id], roomId = this[AiActions.roomId], seq = this[AiActions.seq],
    createdAt = this[AiActions.createdAt], updatedAt = this[AiActions.updatedAt],
    deletedAt = this[AiActions.deletedAt], deletedBy = this[AiActions.deletedBy],
    messageId = this[AiActions.messageId], position = this[AiActions.position],
    kind = fromWire(this[AiActions.kind]), draft = this[AiActions.draft], status = fromWire(this[AiActions.status]),
    resultId = this[AiActions.resultId], decidedBy = this[AiActions.decidedBy], requestedBy = this[AiActions.requestedBy],
)

/**
 * AI 提议、人确认（P8-02）：接受时按草稿建成真正的实体，和提议的状态在同一个事务里提交
 * （锁房间行，两个人同时点「好」也只建一个）。建的人是点「好」的人。
 */
class AiActionService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
    private val events: EventService,
    private val todos: TodoService,
    private val archive: ArchiveService,
    private val ideas: IdeaService,
) {
    private fun action(roomId: UUID, id: UUID): AiAction =
        AiActions.selectAll().where { AiActions.id eq id }.singleOrNull()?.toAiAction()
            ?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()

    /** 已经接受过的原样返回（不管 resultId 是不是这次给的）；不用了的也可以再接受。 */
    suspend fun accept(userId: UUID, roomId: UUID, id: UUID, req: AcceptAiActionRequest): AiAction = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val a = action(roomId, id)
        if (a.status == AiActionStatus.Accepted) return@tx a
        val d = a.draft
        val rid = req.resultId
        when (a.kind) {
            AiActionKind.Event -> events.createIn(this, userId, roomId, CreateEventRequest(
                rid, d.title, d.allDay, note = d.note, location = d.location,
                startsAt = d.startsAt, endsAt = d.endsAt, startDate = d.startDate, endDate = d.endDate,
            ))
            AiActionKind.Todo -> todos.createIn(this, userId, roomId, CreateTodoRequest(
                rid, d.title, note = d.note, assigneeId = d.assigneeId?.takeIf { RoomRepository.isMember(roomId, it) },
                dueDate = d.dueDate, dueAt = d.dueAt, planId = d.planId,
            ))
            AiActionKind.ArchiveItem -> archive.createIn(this, userId, roomId, CreateArchiveItemRequest(rid, d.archiveKind ?: ArchiveKind.Consensus, d.title, d.note.orEmpty()))
            AiActionKind.Idea -> ideas.createIn(this, userId, roomId, CreateIdeaRequest(rid, d.title))
        }
        writes.update(this, roomId, userId, EntityType.AiAction, id, AiActions) {
            it[AiActions.status] = AiActionStatus.Accepted.wireName
            it[AiActions.resultId] = rid
            it[AiActions.decidedBy] = userId
        }
        action(roomId, id)
    }

    suspend fun dismiss(userId: UUID, roomId: UUID, id: UUID): AiAction = db.tx {
        rooms.requireMember(roomId, userId)
        val a = action(roomId, id)
        if (a.status == AiActionStatus.Proposed) {
            writes.update(this, roomId, userId, EntityType.AiAction, id, AiActions) {
                it[AiActions.status] = AiActionStatus.Dismissed.wireName
                it[AiActions.decidedBy] = userId
            }
        }
        action(roomId, id)
    }
}
