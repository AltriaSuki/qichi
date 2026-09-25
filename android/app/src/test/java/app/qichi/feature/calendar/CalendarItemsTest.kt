package app.qichi.feature.calendar

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.shared.api.Event
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.Todo
import app.qichi.shared.model.PlanStatus
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarItemsTest {
    private val room = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val now = Instant.parse("2026-09-21T16:30:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun <T : SyncEntity> local(value: T) = Local(value, SyncState.SYNCED)

    @Test fun `跨日安排、定时截止、计划目标与里程碑按房间日期显示`() {
        val event = local(Event(UUID.randomUUID(), room, 1, now, now, null, null,
            "两天的旅行", null, null, false,
            Instant.parse("2026-09-21T15:30:00Z"), Instant.parse("2026-09-22T02:00:00Z"),
            null, null, emptyList(), me, null))
        val todo = local(Todo(UUID.randomUUID(), room, 2, now, now, null, null,
            "订车票", null, me, me, null, null, now, null, null, null, null, null))
        val plan = local(Plan(UUID.randomUUID(), room, 3, now, now, null, null,
            "秋天去海边", me, PlanStatus.Active, LocalDate.of(2026, 9, 22),
            null, null, null, null, null))
        val milestone = local(Milestone(UUID.randomUUID(), room, 4, now, now, null, null,
            plan.value.id, "订好住处", LocalDate.of(2026, 9, 22), null))
        val items = calendarItems(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 30), zone,
            listOf(event), listOf(todo), listOf(plan), listOf(milestone))

        assertEquals(listOf(event), items[LocalDate.of(2026, 9, 21)]?.events)
        val day = items.getValue(LocalDate.of(2026, 9, 22))
        assertEquals(listOf(event), day.events)
        assertEquals(listOf(todo), day.dueTodos)
        assertEquals(listOf(plan), day.plans)
        assertEquals(listOf(milestone), day.milestones)
    }

    @Test fun `翻到旧月份时仍能显示长日程覆盖的日期`() {
        val event = local(Event(UUID.randomUUID(), room, 1, now, now, null, null,
            "跨季旅行", null, null, true, null, null,
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 1), emptyList(), me, null))
        val items = calendarItems(LocalDate.of(2025, 3, 15), LocalDate.of(2025, 3, 21), zone,
            listOf(event), emptyList(), emptyList(), emptyList())

        assertEquals(7, items.size)
        assertTrue(items.values.all { it.events.single() == event })
    }

    private fun ev(title: String, day: LocalDate, participants: List<UUID> = emptyList()) = local(Event(UUID.randomUUID(), room, 1, now, now, null, null,
        title, null, null, true, null, null, day, day, participants, me, null))

    @Test fun `月份贴纸：这个月今天及以后的第一件日程，标题最多 6 个字；月份已过去时没有`() {
        val sep = LocalDate.of(2026, 9, 1)
        val today = LocalDate.of(2026, 9, 24)
        val items = calendarItems(sep, LocalDate.of(2026, 9, 30), zone,
            listOf(ev("牙医", LocalDate.of(2026, 9, 3)), ev("出发去东山岛看海", LocalDate.of(2026, 9, 26))), emptyList(), emptyList(), emptyList())
        assertEquals(26 to "出发去东山岛…", upcomingInMonth(sep, today, items))
        assertEquals(3 to "牙医", upcomingInMonth(sep, LocalDate.of(2026, 9, 1), items))
        assertEquals(null, upcomingInMonth(sep, LocalDate.of(2026, 10, 2), items))
    }

    @Test fun `格子下的小点：日程按参与的人着色，两个人的两个点；计划和里程碑是菱形；最多三个`() {
        val partner = UUID.randomUUID()
        val room = app.qichi.shared.api.Room(this.room, "家", null, null, null, "Asia/Shanghai", me, 1, now, now)
        fun member(user: UUID) = app.qichi.shared.api.Member(UUID.randomUUID(), this.room, 1, now, now, null, null, user,
            app.qichi.shared.model.MemberRole.Member, "u", "名字", null, now)
        val people = app.qichi.core.data.People(room, listOf(member(me), member(partner)), me)
        val day = LocalDate.of(2026, 9, 24)
        assertEquals(listOf(DayDot.A), dayDots(CalendarDayItems(day, events = listOf(ev("我的", day, listOf(me)))), people))
        assertEquals(listOf(DayDot.B), dayDots(CalendarDayItems(day, events = listOf(ev("对方的", day, listOf(partner)))), people))
        assertEquals(listOf(DayDot.A, DayDot.B, DayDot.Milestone),
            dayDots(CalendarDayItems(day, events = listOf(ev("一起", day)), milestones = listOf(local(Milestone(UUID.randomUUID(), this.room, 4, now, now, null, null, UUID.randomUUID(), "m", day, null)))), people))
    }
}
