package app.qichi.core.reminder

import app.qichi.shared.api.Event
import app.qichi.core.ui.anniversaryOn
import app.qichi.shared.api.Todo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 一条要在 [at] 响的提醒（P16-01）。[key] 在同一件事上不变（改了时间照样是它），用来取消、替换排好的闹钟。
 * [link] 是点开通知时打开的深链。
 */
data class Reminder(
    val key: String,
    val at: Instant,
    val title: String,
    val text: String,
    val link: String,
)

/** 全天日程在早上 9 点提醒（房间时区） */
val ALL_DAY_REMIND_TIME: LocalTime = LocalTime.of(9, 0)

/** 一次最多排这么多个闹钟（系统对一个 App 的闹钟数有上限，两个人的日程远到不了） */
const val MAX_REMINDERS = 50

/** 只排这么远以内的；更远的等之后数据变了、或每天的刷新时再排 */
val REMIND_HORIZON: Duration = Duration.ofDays(8)

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 从本机的日程和待办里算出接下来要响的提醒：
 * - 日程：设了提前多久（[Event.remindMinutes]）、跟我有关（参与的人为空 = 两个人，或者有我）的。
 *   定时的在开始前 N 分钟；全天的小于 1 天时在当天 9 点，1 天时在前一天 9 点（房间时区）。
 * - 待办：定了具体时刻（[Todo.dueAt]）、交给我或两个人、还没做完的，到点提醒；只有日期的不单独提醒。
 * - 纪念日：周年、整百天当天早上 9 点（房间时区），两个人都提醒。
 * 删掉的不提醒；已经过了的不排；按时间排，最多 [MAX_REMINDERS] 个。
 */
fun upcomingReminders(
    roomId: UUID,
    events: List<Event>,
    todos: List<Todo>,
    me: UUID,
    zone: ZoneId,
    now: Instant,
    horizon: Duration = REMIND_HORIZON,
    anniversary: LocalDate? = null,
): List<Reminder> {
    val until = now.plus(horizon)
    // 纪念日：周年、整百天当天早上 9 点提醒两个人（P16-02）
    val fromAnniversary = anniversary?.let { a ->
        val today = now.atZone(zone).toLocalDate()
        generateSequence(today) { it.plusDays(1) }.take(horizon.toDays().toInt() + 1)
            .mapNotNull { day ->
                anniversaryOn(a, day)?.let { text ->
                    Reminder("anniversary:$day", day.atTime(ALL_DAY_REMIND_TIME).atZone(zone).toInstant(), "纪念日", text, "qichi://room/$roomId/today")
                }
            }
    } ?: emptySequence()
    val fromEvents = events.asSequence()
        .filter { it.deletedAt == null && it.remindMinutes != null }
        .filter { it.participantIds.isEmpty() || me in it.participantIds }
        .mapNotNull { e ->
            val minutes = e.remindMinutes!!.toLong()
            if (e.allDay) {
                val start = e.startDate ?: return@mapNotNull null
                val day = if (minutes >= 1440) start.minusDays(1) else start
                val at = day.atTime(ALL_DAY_REMIND_TIME).atZone(zone).toInstant()
                val text = if (minutes >= 1440) "明天全天" else "今天全天"
                Reminder("event:${e.id}", at, e.title, listOfNotNull(text, e.location).joinToString(" · "), "qichi://room/$roomId/calendar")
            } else {
                val start = e.startsAt ?: return@mapNotNull null
                val at = start.minus(Duration.ofMinutes(minutes))
                val lead = when (minutes) {
                    0L -> "现在开始"
                    in 1..59 -> "$minutes 分钟后开始"
                    in 60..1439 -> "${minutes / 60} 小时后开始"
                    else -> "明天 ${start.atZone(zone).format(hm)} 开始"
                }
                Reminder("event:${e.id}", at, e.title, listOfNotNull(lead, e.location).joinToString(" · "), "qichi://room/$roomId/calendar")
            }
        }
    val fromTodos = todos.asSequence()
        .filter { it.deletedAt == null && it.doneAt == null && it.dueAt != null }
        .filter { it.assigneeId == null || it.assigneeId == me }
        .map { t -> Reminder("todo:${t.id}", t.dueAt!!, t.title, "待办到时间了", "qichi://room/$roomId/todo/${t.id}") }
    return (fromEvents + fromTodos + fromAnniversary)
        .filter { it.at.isAfter(now) && !it.at.isAfter(until) }
        .sortedBy { it.at }
        .take(MAX_REMINDERS)
        .toList()
}

/** 编辑面板里「提醒」的几档：null = 不提醒。取值和 Limits.EVENT_REMIND_MINUTES 一致。 */
val REMIND_CHOICES: List<Int?> = listOf(null, 0, 5, 15, 60, 1440)

fun remindLabel(minutes: Int?, allDay: Boolean): String = when {
    minutes == null -> "不提醒"
    allDay -> if (minutes >= 1440) "前一天 9 点" else "当天 9 点"
    minutes == 0 -> "准时"
    minutes < 60 -> "$minutes 分钟前"
    minutes < 1440 -> "${minutes / 60} 小时前"
    else -> "1 天前"
}
