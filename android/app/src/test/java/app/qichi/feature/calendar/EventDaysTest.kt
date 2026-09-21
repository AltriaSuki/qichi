package app.qichi.feature.calendar

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.shared.api.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 跨时区：房间时区 Asia/Shanghai，手机时区不同时，「今天」与日程所在的日子按房间时区计算。 */
class EventDaysTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val original = TimeZone.getDefault()

    @AfterTest
    fun restore() = TimeZone.setDefault(original)

    private fun event(title: String, startsAt: String? = null, endsAt: String? = null, startDate: String? = null, endDate: String? = null) = Local(
        Event(
            id = UUID.randomUUID(), roomId = UUID.randomUUID(), seq = 1, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            deletedAt = null, deletedBy = null, title = title, note = null, location = null, allDay = startDate != null,
            startsAt = startsAt?.let(Instant::parse), endsAt = endsAt?.let(Instant::parse),
            startDate = startDate?.let(LocalDate::parse), endDate = endDate?.let(LocalDate::parse),
            participantIds = emptyList(), createdBy = UUID.randomUUID(), icsUid = null,
        ),
        SyncState.SYNCED,
    )

    @Test
    fun `手机在美属萨摩亚（UTC-11）时，今天按上海算`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
        val now = Instant.parse("2026-09-21T09:00:00Z") // 上海 9-21 17:00，萨摩亚 9-20 22:00
        assertEquals(LocalDate.parse("2026-09-21"), todayIn(shanghai, now))
        assertEquals(LocalDate.parse("2026-09-20"), now.atZone(ZoneId.systemDefault()).toLocalDate())
    }

    @Test
    fun `定时日程按房间时区落到日子上，跨天的每天都出现`() {
        val today = LocalDate.parse("2026-09-21")
        val days = groupEventsByDay(
            listOf(
                event("晚饭", "2026-09-21T11:30:00Z", "2026-09-21T13:00:00Z"),         // 上海 19:30–21:00
                event("夜车", "2026-09-21T15:30:00Z", "2026-09-22T01:00:00Z"),         // 上海 23:30 → 次日 09:00
                event("去海边", startDate = "2026-09-26", endDate = "2026-09-27"),
                event("昨天的", "2026-09-20T02:00:00Z", "2026-09-20T03:00:00Z"),
            ),
            shanghai, today,
        )
        assertEquals(
            listOf("2026-09-21", "2026-09-22", "2026-09-26", "2026-09-27"),
            days.map { it.date.toString() },
        )
        assertEquals(listOf("晚饭", "夜车"), days[0].events.map { it.value.title })
        assertEquals(listOf("夜车"), days[1].events.map { it.value.title })
    }

    @Test
    fun `同一天里全天的在前，定时的按开始时间`() {
        val today = LocalDate.parse("2026-09-21")
        val day = groupEventsByDay(
            listOf(
                event("晚饭", "2026-09-21T11:30:00Z", "2026-09-21T13:00:00Z"),
                event("午饭", "2026-09-21T04:00:00Z", "2026-09-21T05:00:00Z"),
                event("纪念日", startDate = "2026-09-21", endDate = "2026-09-21"),
            ),
            shanghai, today,
        ).single()
        assertEquals(listOf("纪念日", "午饭", "晚饭"), day.events.map { it.value.title })
    }
}
