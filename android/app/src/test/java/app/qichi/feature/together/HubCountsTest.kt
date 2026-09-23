package app.qichi.feature.together

import app.qichi.navigation.Page
import app.qichi.shared.api.Event
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Plan
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.PlanStatus
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class HubCountsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val room = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    // 上海时间 2026-09-23 10:00
    private val now = Instant.parse("2026-09-23T02:00:00Z")
    private val today = LocalDate.of(2026, 9, 23)

    private fun mood(at: Instant) = Mood(UUID.randomUUID(), room, 1, at, at, null, null, me, MoodLabel.entries.first(), 5, null, false)
    private fun todo(due: LocalDate?, done: Boolean = false, parent: UUID? = null) =
        Todo(UUID.randomUUID(), room, 1, now, now, null, null, "t", null, me, null, parent, due, null, null, null, if (done) now else null, null)
    private fun event(start: Instant?, allDay: Boolean = false) =
        Event(UUID.randomUUID(), room, 1, now, now, null, null, "e", null, null, allDay, start, start?.plusSeconds(3600),
            if (allDay) today else null, if (allDay) today else null, emptyList(), me, null)
    private fun plan(status: PlanStatus) = Plan(UUID.randomUUID(), room, 1, now, now, null, null, "p", me, status, null, null, null, null, null, null)
    private fun round(confirmed: List<UUID>, date: LocalDate = today) = QnaRound(UUID.randomUUID(), room, 1, now, now, null, null, UUID.randomUUID(), date, null, confirmed)
    private fun idea() = Idea(UUID.randomUUID(), room, 1, now, now, null, null, me, "i")

    private fun counts(
        moods: List<Mood> = emptyList(), rounds: List<QnaRound> = emptyList(), plans: List<Plan> = emptyList(),
        todos: List<Todo> = emptyList(), events: List<Event> = emptyList(), ideas: List<Idea> = emptyList(),
    ) = hubCounts(HubData(me, zone, now, moods, rounds, plans, todos, events, ideas))

    @Test
    fun `什么都没有时目录不显示数字`() {
        assertEquals(emptyMap(), counts())
    }

    @Test
    fun `心情只数房间时区的今天；待办数到期和逾期的顶层未完成待办`() {
        val c = counts(
            // 上海 9-23 00:30 算今天；UTC 9-22 15:00 是上海 9-22 23:00，不算
            moods = listOf(mood(Instant.parse("2026-09-22T16:30:00Z")), mood(Instant.parse("2026-09-22T15:00:00Z")), mood(now)),
            todos = listOf(todo(today), todo(today.minusDays(2)), todo(today.plusDays(1)), todo(null), todo(today, done = true), todo(today, parent = UUID.randomUUID())),
        )
        assertEquals("2", c[Page.Mood])
        assertEquals("2", c[Page.Todo])
    }

    @Test
    fun `问答在我还没确认今天的回答时为 1；计划只数进行中；灵感数总数`() {
        assertEquals("1", counts(rounds = listOf(round(listOf(partner))))[Page.Qna])
        assertEquals(null, counts(rounds = listOf(round(listOf(me))))[Page.Qna])
        assertEquals(null, counts(rounds = listOf(round(emptyList(), today.minusDays(1))))[Page.Qna], "昨天的轮次不算")
        assertEquals("2", counts(plans = listOf(plan(PlanStatus.Active), plan(PlanStatus.Active), plan(PlanStatus.Done)))[Page.Plan])
        assertEquals("3", counts(ideas = List(3) { idea() })[Page.Ideas])
    }

    @Test
    fun `日历显示今天接下来第一个日程的开始时间；已经开始的、全天的、明天的不算`() {
        val c = counts(
            events = listOf(
                event(Instant.parse("2026-09-23T01:00:00Z")), // 09:00 已过
                event(Instant.parse("2026-09-23T11:30:00Z")), // 19:30
                event(Instant.parse("2026-09-23T06:00:00Z")), // 14:00
                event(null, allDay = true),
                event(Instant.parse("2026-09-24T01:00:00Z")),
            ),
        )
        assertEquals("14:00", c[Page.Calendar])
        assertEquals(null, counts(events = listOf(event(Instant.parse("2026-09-23T16:30:00Z"))))[Page.Calendar], "上海已是明天")
    }
}
