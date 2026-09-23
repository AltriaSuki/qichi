package app.qichi.feature.together

import app.qichi.core.data.days
import app.qichi.navigation.Page
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Event
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Plan
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Todo
import app.qichi.shared.model.PlanStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/** 算目录右侧数字要用到的东西。 */
data class HubData(
    val me: UUID?,
    val zone: ZoneId,
    val now: Instant,
    val moods: List<Mood>,
    val rounds: List<QnaRound>,
    val plans: List<Plan>,
    val todos: List<Todo>,
    val events: List<Event>,
    val ideas: List<Idea>,
    val decisions: List<Decision> = emptyList(),
)

/**
 * 「一起 · 生活」目录右侧的数字（按房间时区算「今天」），没有就不显示：
 * 心情 = 今天两人记下的心情条数；问答 = 今天的问题我还没确认回答时为 1；
 * 计划 = 进行中的计划数；待办 = 截止在今天或更早、还没完成的顶层待办数；
 * 日历 = 今天接下来第一个有具体时间的日程几点开始；灵感 = 灵感总数。
 * 「回看」里：决定 = 还没定的加上复查日期到了的。
 */
fun hubCounts(d: HubData): Map<Page, String> {
    val today: LocalDate = d.now.atZone(d.zone).toLocalDate()
    fun dueOf(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(d.zone)?.toLocalDate()
    val counts = mapOf(
        Page.Mood to d.moods.count { it.createdAt.atZone(d.zone).toLocalDate() == today },
        Page.Qna to if (d.rounds.any { it.roundDate == today && d.me != null && d.me !in it.confirmedBy }) 1 else 0,
        Page.Plan to d.plans.count { it.status != PlanStatus.Done },
        Page.Todo to d.todos.count { it.parentId == null && it.doneAt == null && dueOf(it)?.let { due -> !due.isAfter(today) } == true },
        Page.Ideas to d.ideas.size,
        Page.Decisions to d.decisions.count { it.finalChoice == null || it.reviewDate?.let { r -> !r.isAfter(today) } == true },
    ).filterValues { it > 0 }.mapValues { it.value.toString() }
    val nextEvent = d.events
        .filter { !it.allDay && it.startsAt != null && it.startsAt!! > d.now && today in it.days(d.zone) }
        .minByOrNull { it.startsAt!! }
        ?.startsAt?.atZone(d.zone)?.takeIf { it.toLocalDate() == today }?.format(hm)
    return if (nextEvent != null) counts + (Page.Calendar to nextEvent) else counts
}
