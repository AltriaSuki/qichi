package app.qichi.core.ui

import app.qichi.core.data.People
import app.qichi.core.sync.Local
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.rules.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

// 待办的编辑表单：待办页和计划页（P14-03，计划里的待办点开就能改）共用；面板在 TodoEditor.kt。

enum class Assignee { Both, Me, Partner }
enum class Repeat(val label: String) { None("不重复"), Daily("每天"), Weekly("每周"), Monthly("每月") }

/**
 * 编辑表单。重复需要截止日：选了重复却没选日期时，截止日取今天（[fixed]）。
 * 截止可以只有日期，也可以再加一个时刻 [dueTime]（有时刻的到点提醒，P16-01）；发给服务端时二选一：有时刻发 dueAt，没有发 dueDate。
 */
data class TodoForm(
    val title: String = "",
    val note: String = "",
    val assignee: Assignee = Assignee.Both,
    val dueDate: LocalDate? = null,
    /** 截止的时刻；只在有 [dueDate] 时有意义 */
    val dueTime: LocalTime? = null,
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

    /** 选了重复却没选日期：截止日取今天。没有日期时时刻也不要。 */
    fun fixed(today: LocalDate): TodoForm {
        val date = dueDate ?: if (repeat != Repeat.None) today else null
        return copy(dueDate = date, dueTime = if (date == null) null else dueTime)
    }

    /** 发给服务端的截止：（只有日期的 dueDate，有时刻的 dueAt），至多一个有值。先 [fixed] 再调。 */
    fun due(zone: ZoneId): Pair<LocalDate?, Instant?> {
        val date = dueDate ?: return null to null
        val time = dueTime ?: return date to null
        return null to date.atTime(time).atZone(zone).toInstant()
    }

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
            // 截止没动就不发（时刻按分钟比，秒数不同不算改了）；动了按「日期 / 时刻二选一」发
            dueDate = due(zone).first.let { if (it != todo.dueDate) Patch.of(it) else Patch.Absent },
            dueAt = due(zone).second.let { if (it != todo.dueAt?.truncatedTo(ChronoUnit.MINUTES)) Patch.of(it) else Patch.Absent },
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
            dueTime = todo.dueAt?.atZone(zone)?.toLocalTime()?.truncatedTo(ChronoUnit.MINUTES),
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
