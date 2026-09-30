package app.qichi.feature.widget

import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 桌面待办组件上的一行（P15-02）。
 * [due]：截止那天（定了时刻的按房间时区折算）。
 */
data class WidgetRow(val todo: Todo, val due: LocalDate, val overdue: Boolean)

/**
 * 组件列哪些、按什么顺序（P15-02，人类 2026-09-27 选定）：
 * - 今天到期和过期的顶层待办（子任务跟着父待办，不单独列；删掉的不列）；
 * - 交给我的和交给两个人的（只交给对方的不列）；
 * - 只列没做完的；勾选完成后立即消失；
 * - 过了期的在前（早的先）；同一天里定了时刻的按时刻、排在只有日期的前面；再按创建先后。
 */
fun widgetRows(todos: List<Todo>, me: UUID, today: LocalDate, zone: ZoneId): List<WidgetRow> {
    return todos.asSequence()
        .filter { it.parentId == null && it.deletedAt == null }
        .filter { it.assigneeId == null || it.assigneeId == me }
        .filter { it.doneAt == null }
        .mapNotNull { t ->
            val due = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate() ?: return@mapNotNull null
            if (due.isAfter(today)) null else WidgetRow(t, due, overdue = due.isBefore(today))
        }
        .sortedWith(compareBy<WidgetRow>({ it.due }, { it.todo.dueAt == null }, { it.todo.dueAt }, { it.todo.createdAt }))
        .toList()
}

/**
 * 组件列表里这一行的编号：由待办 id 算出，每条不同，而且不是负数。
 * Glance 把很大的负数留给自己用，传进去就整个组件报错、只显示「无法显示内容」；
 * UUIDv7 两半直接异或恰好总落在那一段（前半首位是 0、后半首位是 1），所以去掉符号位。
 */
fun widgetItemId(id: UUID): Long = (id.mostSignificantBits xor id.leastSignificantBits) and Long.MAX_VALUE

/** 还有几件没做。 */
fun List<WidgetRow>.remaining(): Int = size

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 一行右边的小字：过了期的写哪天（昨天、09.20），今天定了时刻的写几点；只有今天这个日期的不写。
 */
fun widgetDueLabel(row: WidgetRow, today: LocalDate, zone: ZoneId): String? = when {
    row.overdue -> relativeDay(row.due, today).first
    else -> row.todo.dueAt?.atZone(zone)?.toLocalTime()?.format(HH_MM)
}

/** 这件是不是已经晚了：过了期的，或者今天定的时刻已经过了（右边的小字用暮玫瑰色）。 */
fun widgetIsLate(row: WidgetRow, now: Instant): Boolean =
    row.overdue || row.todo.dueAt?.isBefore(now) == true
