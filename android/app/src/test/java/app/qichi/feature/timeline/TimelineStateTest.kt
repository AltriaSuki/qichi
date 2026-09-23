package app.qichi.feature.timeline

import app.qichi.shared.api.TimelineMonthCount
import app.qichi.shared.api.TimelinePage
import org.junit.Test
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineStateTest {
    private val months = listOf(TimelineMonthCount(2026, 9, 3), TimelineMonthCount(2026, 6, 1), TimelineMonthCount(2025, 12, 2))

    private fun at(y: Int, m: Int) = TimelineState(page = TimelinePage(y, m, emptyList(), months))

    @Test
    fun `前后翻只落在有内容的月份上`() {
        assertEquals(YearMonth.of(2026, 6), at(2026, 9).older)
        assertNull(at(2026, 9).newer)
        assertEquals(YearMonth.of(2025, 12), at(2026, 6).older)
        assertEquals(YearMonth.of(2026, 9), at(2026, 6).newer)
        assertNull(at(2025, 12).older)
    }

    @Test
    fun `停在一个空的月份时，前后跳到最近的有内容的月份`() {
        assertEquals(YearMonth.of(2026, 6), at(2026, 8).older)
        assertEquals(YearMonth.of(2026, 9), at(2026, 8).newer)
    }
}
