package app.qichi.feature.timeline

import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.model.TimelineEntryKind
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class TimelineDaysTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun e(kind: TimelineEntryKind, at: String) = TimelineEntry(kind, UUID.randomUUID(), Instant.parse(at), kind.name)

    @Test
    fun `按房间时区分天，新的一天在前；一天里心情、照片、其余分开`() {
        val days = timelineDays(
            listOf(
                e(TimelineEntryKind.Decision, "2026-09-20T02:00:00Z"),
                e(TimelineEntryKind.Photo, "2026-09-24T03:00:00Z"),
                e(TimelineEntryKind.Mood, "2026-09-24T01:00:00Z"),
                // 上海 9-24 00:30 算 24 号
                e(TimelineEntryKind.Idea, "2026-09-23T16:30:00Z"),
            ),
            zone,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 20)), days.map { it.date })
        val first = days.first()
        assertEquals(listOf(TimelineEntryKind.Mood), first.moods.map { it.kind })
        assertEquals(listOf(TimelineEntryKind.Photo), first.photos.map { it.kind })
        assertEquals(listOf(TimelineEntryKind.Idea), first.others.map { it.kind })
    }
}
