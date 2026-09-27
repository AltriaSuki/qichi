package app.qichi.feature.widget

import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Todo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 桌面待办组件上的一行（P15-02）。
 * [due]：截止那天（定了时刻的按房间时区折算）；[justDone]：我刚勾掉的，划线留一会儿，旁边是「撤回」。
 */
data class WidgetRow(val todo: Todo, val due: LocalDate, val overdue: Boolean, val justDone: Boolean)

/** 勾掉以后划线留多久：这段时间里点「撤回」就回来，过了就从组件上消失。 */
val JUST_DONE_FOR: Duration = Duration.ofMinutes(3)

/**
 * 组件列哪些、按什么顺序（P15-02，人类 2026-09-27 选定）：
 * - 今天到期和过期的顶层待办（子任务跟着父待办，不单独列；删掉的不列）；
 * - 交给我的和交给两个人的（只交给对方的不列）；
 * - 没做完的，加上我刚勾掉的（[JUST_DONE_FOR] 之内）：留在原位划线，可以撤回；
 * - 过了期的在前（早的先）；同一天里定了时刻的按时刻、排在只有日期的前面；再按创建先后。
 */
fun widgetRows(todos: List<Todo>, me: UUID, today: LocalDate, zone: ZoneId, now: Instant): List<WidgetRow> {
    val recent = now.minus(JUST_DONE_FOR)
    return todos.asSequence()
        .filter { it.parentId == null && it.deletedAt == null }
        .filter { it.assigneeId == null || it.assigneeId == me }
        .filter { t -> t.doneAt.let { done -> done == null || (t.doneBy == me && done >= recent) } }
        .mapNotNull { t ->
            val due = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate() ?: return@mapNotNull null
            if (due.isAfter(today)) null else WidgetRow(t, due, overdue = due.isBefore(today), justDone = t.doneAt != null)
        }
        .sortedWith(compareBy<WidgetRow>({ it.due }, { it.todo.dueAt == null }, { it.todo.dueAt }, { it.todo.createdAt }))
        .toList()
}

/** 还有几件没做（刚勾掉的不算）。 */
fun List<WidgetRow>.remaining(): Int = count { !it.justDone }

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 一行右边的小字：过了期的写哪天（昨天、09.20），今天定了时刻的写几点；只有今天这个日期的不写。
 * 刚勾掉的不写（右边是「撤回」）。
 */
fun widgetDueLabel(row: WidgetRow, today: LocalDate, zone: ZoneId): String? = when {
    row.justDone -> null
    row.overdue -> relativeDay(row.due, today).first
    else -> row.todo.dueAt?.atZone(zone)?.toLocalTime()?.format(HH_MM)
}

/** 这件是不是已经晚了：过了期的，或者今天定的时刻已经过了（右边的小字用暮玫瑰色）。 */
fun widgetIsLate(row: WidgetRow, now: Instant): Boolean =
    !row.justDone && (row.overdue || row.todo.dueAt?.isBefore(now) == true)
