package app.qichi.server.todos

import app.qichi.server.db.Todos
import app.qichi.shared.api.Todo
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toTodo() = Todo(
    id = this[Todos.id],
    roomId = this[Todos.roomId],
    seq = this[Todos.seq],
    createdAt = this[Todos.createdAt],
    updatedAt = this[Todos.updatedAt],
    deletedAt = this[Todos.deletedAt],
    deletedBy = this[Todos.deletedBy],
    title = this[Todos.title],
    note = this[Todos.note],
    createdBy = this[Todos.createdBy],
    assigneeId = this[Todos.assigneeId],
    parentId = this[Todos.parentId],
    dueDate = this[Todos.dueDate],
    dueAt = this[Todos.dueAt],
    recurrence = this[Todos.recurrence],
    recurrencePrevId = this[Todos.recurrencePrevId],
    doneAt = this[Todos.doneAt],
    doneBy = this[Todos.doneBy],
)
