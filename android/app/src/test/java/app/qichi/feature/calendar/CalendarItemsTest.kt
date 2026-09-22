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
}
