package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 待办的本机读写：先写本机（界面立即变化）再经发件箱发出，离线也能新建和完成。 */
class TodoRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    /** 房间里的待办（不含回收站）。 */
    fun observeTodos(roomId: UUID): Flow<List<Local<Todo>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Todo.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Todo>(it) } }

    /** 某天截止的待办；定时截止按房间时区折算，计划下的待办也显示。 */
    fun observeTodosForDate(roomId: UUID, date: LocalDate, zone: ZoneId): Flow<List<Local<Todo>>> =
        observeTodos(roomId).map { todos ->
            todos.filter { local ->
                val todo = local.value
                todo.deletedAt == null &&
                    (todo.dueDate == date || todo.dueAt?.atZone(zone)?.toLocalDate() == date)
            }
        }

    suspend fun create(
        roomId: UUID,
        title: String,
        assigneeId: UUID? = null,
        parentId: UUID? = null,
        dueDate: LocalDate? = null,
        dueAt: Instant? = null,
        recurrence: String? = null,
        note: String? = null,
    ): Todo {
        val now = clock.instant()
        val todo = Todo(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            title = title.trim(), note = note?.trim()?.takeIf { it.isNotEmpty() }, createdBy = me, assigneeId = assigneeId,
            parentId = parentId, dueDate = dueDate, dueAt = dueAt, recurrence = recurrence, recurrencePrevId = null,
            doneAt = null, doneBy = null, planId = null,
        )
        store.writeLocal(
            roomId, todo,
            OutboxOp.post(
                "rooms/$roomId/todos",
                CreateTodoRequest(todo.id, todo.title, todo.note, assigneeId, parentId, dueDate, dueAt, recurrence),
            ),
        )
        scheduler.kickOutbox()
        return todo
    }

    /** 修改：本机按改动字段更新，发出的 PATCH 只含改动的字段。 */
    suspend fun update(todo: Todo, change: UpdateTodoRequest) {
        var updated = todo.copy(updatedAt = clock.instant())
        (change.title as? Patch.Value)?.let { updated = updated.copy(title = it.value.trim()) }
        (change.note as? Patch.Value)?.let { updated = updated.copy(note = it.value?.trim()?.takeIf { n -> n.isNotEmpty() }) }
        (change.assigneeId as? Patch.Value)?.let { updated = updated.copy(assigneeId = it.value) }
        (change.dueDate as? Patch.Value)?.let { updated = updated.copy(dueDate = it.value) }
        (change.dueAt as? Patch.Value)?.let { updated = updated.copy(dueAt = it.value) }
        (change.recurrence as? Patch.Value)?.let { updated = updated.copy(recurrence = it.value) }
        store.writeLocal(todo.roomId, updated, OutboxOp.patch("rooms/${todo.roomId}/todos/${todo.id}", change))
        scheduler.kickOutbox()
    }

    /** 完成。重复待办带上下一次实例的 id，服务端据此生成（联网后出现在列表里）。 */
    suspend fun complete(todo: Todo) {
        val now = clock.instant()
        val body = CompleteTodoRequest(nextId = if (todo.recurrence != null) UuidV7.generate() else null)
        store.writeLocal(
            todo.roomId, todo.copy(doneAt = now, doneBy = me, updatedAt = now),
            OutboxOp.post("rooms/${todo.roomId}/todos/${todo.id}/complete", body, kind = OutboxOp.KIND_TODO_COMPLETE),
        )
        scheduler.kickOutbox()
    }

    suspend fun reopen(todo: Todo) {
        val now = clock.instant()
        store.writeLocal(todo.roomId, todo.copy(doneAt = null, doneBy = null, updatedAt = now), OutboxOp.action("rooms/${todo.roomId}/todos/${todo.id}/reopen"))
        scheduler.kickOutbox()
    }

    /** 删除进回收站；子任务在本机乐观地一起显示为删除（服务端会一起删，之后的拉取确认）。 */
    suspend fun delete(todo: Todo, children: List<Todo>) {
        val now = clock.instant()
        db.transaction {
            store.writeLocal(todo.roomId, todo.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${todo.roomId}/todos/${todo.id}"))
            children.filter { it.deletedAt == null }.forEach { store.applyOptimistic(it.copy(deletedAt = now, deletedBy = me)) }
        }
        scheduler.kickOutbox()
    }
}
