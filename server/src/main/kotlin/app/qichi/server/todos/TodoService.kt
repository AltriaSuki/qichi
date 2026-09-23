package app.qichi.server.todos

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Rooms
import app.qichi.server.db.Todos
import app.qichi.server.db.Tx
import app.qichi.server.db.tx
import app.qichi.server.plugins.Validator
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CompleteTodoResponse
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.api.ifPresent
import app.qichi.shared.model.EntityType
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.Recurrence
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 待办（P2-02）：新建、修改、完成（重复待办按 nextId 生成下一次）、取消完成、删除；子任务只有一层。
 */
class TodoService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun todo(id: UUID): Todo? = Todos.selectAll().where { Todos.id eq id }.singleOrNull()?.toTodo()

    private fun existingInRoom(roomId: UUID, id: UUID): Todo =
        todo(id)?.takeIf { it.roomId == roomId } ?: notFound()

    suspend fun create(userId: UUID, roomId: UUID, req: CreateTodoRequest): Pair<Todo, Boolean> {
        val title = req.title.trim()
        val note = req.note?.trim()?.takeIf { it.isNotEmpty() }
        validate {
            checkTitle(title)
            checkNote(note)
            check(req.dueDate == null || req.dueAt == null, "dueAt", "只能设日期或时刻其中一个")
            checkRecurrence(req.recurrence, hasDue = req.dueDate != null || req.dueAt != null)
            check(req.parentId == null || req.recurrence == null, "recurrence", "子任务不能重复")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            validateRefs(roomId, req.assigneeId, req.parentId, req.planId, selfId = req.id)
            writes.create(this, roomId, userId, EntityType.Todo, req.id, Todos, ::todo) {
                it[Todos.title] = title
                it[Todos.note] = note
                it[Todos.createdBy] = userId
                it[Todos.assigneeId] = req.assigneeId
                it[Todos.parentId] = req.parentId
                it[Todos.dueDate] = req.dueDate
                it[Todos.dueAt] = req.dueAt
                it[Todos.recurrence] = req.recurrence?.let { r -> Recurrence.parse(r)!!.format() }
                it[Todos.planId] = req.planId
            }
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateTodoRequest): Todo = db.tx {
        rooms.requireMember(roomId, userId)
        val current = existingInRoom(roomId, id)
        val title = (req.title as? Patch.Value)?.value?.trim() ?: current.title
        val note = if (req.note.isPresent) req.note.orNull()?.trim()?.takeIf { it.isNotEmpty() } else current.note
        val dueDate = if (req.dueDate.isPresent) req.dueDate.orNull() else current.dueDate
        val dueAt = if (req.dueAt.isPresent) req.dueAt.orNull() else current.dueAt
        val recurrence = if (req.recurrence.isPresent) req.recurrence.orNull()?.takeIf { it.isNotBlank() } else current.recurrence
        validate {
            checkTitle(title)
            checkNote(note)
            check(dueDate == null || dueAt == null, "dueAt", "只能设日期或时刻其中一个")
            checkRecurrence(recurrence, hasDue = dueDate != null || dueAt != null)
            check(current.parentId == null || recurrence == null, "recurrence", "子任务不能重复")
        }
        req.assigneeId.ifPresent { validateRefs(roomId, it, null, null, selfId = id) }
        req.planId.ifPresent { validateRefs(roomId, null, null, it, selfId = id) }
        writes.update(this, roomId, userId, EntityType.Todo, id, Todos) {
            it[Todos.title] = title
            it[Todos.note] = note
            req.assigneeId.ifPresent { a -> it[Todos.assigneeId] = a }
            it[Todos.dueDate] = dueDate
            it[Todos.dueAt] = dueAt
            it[Todos.recurrence] = recurrence?.let { r -> Recurrence.parse(r)!!.format() }
            req.planId.ifPresent { p -> it[Todos.planId] = p }
        }
        todo(id)!!
    }

    /**
     * 完成。重复待办必须带 nextId：按规则算出下一次截止并生成新实例（recurrencePrevId 指向本条）。
     * 已完成的再提交一次：返回当前状态与已生成的下一次，不会生成第二个。
     */
    suspend fun complete(userId: UUID, roomId: UUID, id: UUID, req: CompleteTodoRequest): CompleteTodoResponse = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val current = existingInRoom(roomId, id)
        if (current.doneAt != null) {
            return@tx CompleteTodoResponse(current, nextOf(id))
        }
        val rule = current.recurrence?.let { Recurrence.parse(it) }
        if (rule != null) validate { check(req.nextId != null, "nextId", "重复待办需要 nextId") }

        val now = writes.now()
        writes.update(this, roomId, userId, EntityType.Todo, id, Todos) {
            it[Todos.doneAt] = now
            it[Todos.doneBy] = userId
        }
        val next = if (rule != null) createNext(this, roomId, userId, current, rule, req.nextId!!) else null
        CompleteTodoResponse(todo(id)!!, next)
    }

    /** 取消完成；已生成的下一次实例保留不动。 */
    suspend fun reopen(userId: UUID, roomId: UUID, id: UUID): Todo = db.tx {
        rooms.requireMember(roomId, userId)
        val current = existingInRoom(roomId, id)
        if (current.doneAt != null) {
            writes.update(this, roomId, userId, EntityType.Todo, id, Todos) {
                it[Todos.doneAt] = null
                it[Todos.doneBy] = null
            }
        }
        todo(id)!!
    }

    /** 删除进回收站；子任务一起进（同一个删除时间，恢复时一起回来）。 */
    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Todo = db.tx {
        rooms.requireMember(roomId, userId)
        val current = existingInRoom(roomId, id)
        if (current.deletedAt == null) {
            val at = writes.now()
            writes.softDelete(this, roomId, userId, EntityType.Todo, id, Todos, at)
            Todos.select(Todos.id)
                .where { (Todos.parentId eq id) and Todos.deletedAt.isNull() }
                .map { it[Todos.id] }
                .forEach { child -> writes.softDelete(this, roomId, userId, EntityType.Todo, child, Todos, at) }
        }
        todo(id)!!
    }

    /** 恢复父待办时，一起删除的子任务也回来（回收站用）。 */
    fun restoreWithChildren(tx: Tx, roomId: UUID, userId: UUID, id: UUID) {
        val current = todo(id) ?: return
        val deletedAt = current.deletedAt ?: return
        writes.restore(tx, roomId, userId, EntityType.Todo, id, Todos)
        Todos.select(Todos.id)
            .where { (Todos.parentId eq id) and (Todos.deletedAt eq deletedAt) }
            .map { it[Todos.id] }
            .forEach { child -> writes.restore(tx, roomId, userId, EntityType.Todo, child, Todos) }
    }

    private fun nextOf(id: UUID): Todo? =
        Todos.selectAll().where { Todos.recurrencePrevId eq id }.singleOrNull()?.toTodo()

    private fun createNext(tx: Tx, roomId: UUID, userId: UUID, current: Todo, rule: Recurrence, nextId: UUID): Todo {
        nextOf(current.id)?.let { return it }
        val zone = ZoneId.of(Rooms.select(Rooms.timezone).where { Rooms.id eq roomId }.single()[Rooms.timezone])
        val nextDueDate: LocalDate? = current.dueDate?.let(rule::next)
        val nextDueAt: Instant? = current.dueAt?.let { at ->
            val local = at.atZone(zone)
            local.with(rule.next(local.toLocalDate())).toInstant()
        }
        return writes.create(tx, roomId, userId, EntityType.Todo, nextId, Todos, ::todo) {
            it[Todos.title] = current.title
            it[Todos.note] = current.note
            it[Todos.createdBy] = userId
            it[Todos.assigneeId] = current.assigneeId
            it[Todos.dueDate] = nextDueDate
            it[Todos.dueAt] = nextDueAt
            it[Todos.recurrence] = current.recurrence
            it[Todos.recurrencePrevId] = current.id
            it[Todos.planId] = current.planId
        }.first
    }

    /** 指派的人必须是房间成员；父待办必须在同一房间、未删除、本身不是子任务。 */
    private fun validateRefs(roomId: UUID, assigneeId: UUID?, parentId: UUID?, planId: UUID?, selfId: UUID) {
        validate {
            if (assigneeId != null) check(RoomRepository.isMember(roomId, assigneeId), "assigneeId", "只能指派给房间里的人")
            if (parentId != null) {
                val parent = todo(parentId)
                check(
                    parent != null && parent.roomId == roomId && parent.deletedAt == null && parent.parentId == null && parentId != selfId,
                    "parentId",
                    "父待办不存在或本身是子任务",
                )
            }
            if (planId != null) {
                val plan = Plans.selectAll().where { (Plans.id eq planId) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }
                    .singleOrNull()
                check(plan != null, "planId", "计划不存在或已删除")
            }
        }
    }

    private fun Validator.checkTitle(title: String) =
        check(title.length in Limits.TODO_TITLE_LENGTH, "title", "标题 1–200 个字")

    private fun Validator.checkNote(note: String?) =
        check((note?.length ?: 0) <= Limits.NOTE_MAX, "note", "备注最多 ${Limits.NOTE_MAX} 字")

    private fun Validator.checkRecurrence(rule: String?, hasDue: Boolean) {
        if (rule == null) return
        check(Recurrence.parse(rule) != null, "recurrence", "不支持的重复规则")
        check(hasDue, "recurrence", "重复待办需要截止日")
    }
}
