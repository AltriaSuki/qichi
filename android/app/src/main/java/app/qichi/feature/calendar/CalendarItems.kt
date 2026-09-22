package app.qichi.feature.calendar

import app.qichi.core.sync.Local
import app.qichi.shared.api.Event
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.Todo
import java.time.LocalDate
import java.time.ZoneId

/** 日历当天的四类内容；所有内容只来自本机 Room。 */
data class CalendarDayItems(
    val date: LocalDate,
    val events: List<Local<Event>> = emptyList(),
    val dueTodos: List<Local<Todo>> = emptyList(),
    val plans: List<Local<Plan>> = emptyList(),
    val milestones: List<Local<Milestone>> = emptyList(),
) {
    val hasContent: Boolean get() = events.isNotEmpty() || dueTodos.isNotEmpty() || plans.isNotEmpty() || milestones.isNotEmpty()
}

internal data class CalendarSources(
    val events: List<Local<Event>>,
    val todos: List<Local<Todo>>,
    val plans: List<Local<Plan>>,
    val milestones: List<Local<Milestone>>,
)

/** 只展开当前可见日期范围，长日程也能在经过的每一天显示。 */
fun calendarItems(
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId,
    events: List<Local<Event>>,
    todos: List<Local<Todo>>,
    plans: List<Local<Plan>>,
    milestones: List<Local<Milestone>>,
): Map<LocalDate, CalendarDayItems> {
    require(!end.isBefore(start))
    val result = mutableMapOf<LocalDate, CalendarDayItems>()
    fun add(date: LocalDate, update: (CalendarDayItems) -> CalendarDayItems) {
        if (date.isBefore(start) || date.isAfter(end)) return
        result[date] = update(result[date] ?: CalendarDayItems(date))
    }
    events.forEach { local ->
        val event = local.value
        val first = if (event.allDay) event.startDate else event.startsAt?.atZone(zone)?.toLocalDate()
        val last = if (event.allDay) event.endDate else event.endsAt?.atZone(zone)?.toLocalDate()
        if (first != null && last != null && !last.isBefore(first)) {
            var day = maxOf(first, start)
            val through = minOf(last, end)
            while (!day.isAfter(through)) {
                add(day) { it.copy(events = it.events + local) }
                day = day.plusDays(1)
            }
        }
    }
    todos.forEach { local ->
        (local.value.dueDate ?: local.value.dueAt?.atZone(zone)?.toLocalDate())?.let { date ->
            add(date) { it.copy(dueTodos = it.dueTodos + local) }
        }
    }
    plans.forEach { local ->
        local.value.targetDate?.let { date -> add(date) { it.copy(plans = it.plans + local) } }
    }
    milestones.forEach { local ->
        local.value.targetDate?.let { date -> add(date) { it.copy(milestones = it.milestones + local) } }
    }
    return result
}
