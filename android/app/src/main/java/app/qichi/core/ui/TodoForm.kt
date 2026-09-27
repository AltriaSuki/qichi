package app.qichi.core.ui

import app.qichi.core.data.People
import app.qichi.core.sync.Local
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.rules.Recurrence
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// 待办的编辑表单：待办页和计划页（P14-03，计划里的待办点开就能改）共用；面板在 TodoEditor.kt。

enum class Assignee { Both, Me, Partner }
enum class Repeat(val label: String) { None("不重复"), Daily("每天"), Weekly("每周"), Monthly("每月") }

/** 编辑表单。重复需要截止日：选了重复却没选日期时，截止日取今天（[fixed]）。 */
data class TodoForm(
    val title: String = "",
    val note: String = "",
    val assignee: Assignee = Assignee.Both,
    val dueDate: LocalDate? = null,
    val repeat: Repeat = Repeat.None,
    /** 属于哪个计划；空 = 不属于 */
    val planId: UUID? = null,
) {
    val canSave: Boolean get() = title.trim().isNotEmpty()

    fun recurrence(): String? {
        val due = dueDate ?: return null
        return when (repeat) {
            Repeat.None -> null
            Repeat.Daily -> Recurrence(Recurrence.Freq.DAILY).format()
            Repeat.Weekly -> Recurrence(Recurrence.Freq.WEEKLY, byDay = setOf(due.dayOfWeek)).format()
            Repeat.Monthly -> Recurrence(Recurrence.Freq.MONTHLY).format()
        }
    }

    /** 选了重复却没选日期：截止日取今天。 */
    fun fixed(today: LocalDate): TodoForm = copy(dueDate = dueDate ?: if (repeat != Repeat.None) today else null)

    fun assigneeId(people: People): UUID? = when (assignee) {
        Assignee.Both -> null
        Assignee.Me -> people.myUserId
        Assignee.Partner -> people.partner?.userId
    }

    /** 和 [todo] 比，改了哪些字段（只发改动过的）。先 [fixed] 再调。 */
    fun changesFrom(todo: Todo, people: People, zone: ZoneId): UpdateTodoRequest {
        val before = of(todo, people, zone)
        return UpdateTodoRequest(
            title = if (title.trim() != todo.title) Patch.of(title.trim()) else Patch.Absent,
            note = if (note.trim() != todo.note.orEmpty()) Patch.of(note.trim().ifEmpty { null }) else Patch.Absent,
            assigneeId = if (assignee != before.assignee) Patch.of(assigneeId(people)) else Patch.Absent,
            // 编辑面板只有日期：日期没动就不发（有具体时刻的待办时刻留着）；动了换成只有日期、时刻清掉
            dueDate = if (dueDate != before.dueDate) Patch.of(dueDate) else Patch.Absent,
            dueAt = if (todo.dueAt != null && dueDate != before.dueDate) Patch.of(null) else Patch.Absent,
            recurrence = if (recurrence() != todo.recurrence) Patch.of(recurrence()) else Patch.Absent,
            planId = if (planId != todo.planId) Patch.of(planId) else Patch.Absent,
        )
    }

    companion object {
        fun of(todo: Todo, people: People, zone: ZoneId): TodoForm = TodoForm(
            title = todo.title,
            note = todo.note.orEmpty(),
            assignee = when (todo.assigneeId) {
                null -> Assignee.Both
                people.myUserId -> Assignee.Me
                else -> Assignee.Partner
            },
            dueDate = todo.dueDate ?: todo.dueAt?.atZone(zone)?.toLocalDate(),
            repeat = when (todo.recurrence?.let(Recurrence::parse)?.freq) {
                Recurrence.Freq.DAILY -> Repeat.Daily
                Recurrence.Freq.WEEKLY -> Repeat.Weekly
                Recurrence.Freq.MONTHLY -> Repeat.Monthly
                null -> Repeat.None
            },
            planId = todo.planId,
        )
    }
}

/** 一条顶层待办和它的子任务。 */
data class TodoGroup(val todo: Local<Todo>, val children: List<Local<Todo>>)
