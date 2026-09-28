package app.qichi.core.ui

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 纪念日：在一起第几天、周年和整百天、倒数（P16-02）。 */
class AnniversaryTest {
    private val start = LocalDate.of(2024, 3, 10)

    @Test
    fun `纪念日当天是第 1 天，之后逐天加一`() {
        assertEquals(AnniversaryLine("在一起第 1 天", false), anniversaryLine(start, start))
        assertEquals(AnniversaryLine("在一起第 2 天", false), anniversaryLine(start, start.plusDays(1)))
        assertEquals(931, daysTogether(start, LocalDate.of(2026, 9, 26)))
    }

    @Test
    fun `周年和整百天当天醒目地写出来`() {
        assertEquals(AnniversaryLine("今天是在一起 2 周年", true), anniversaryLine(start, LocalDate.of(2026, 3, 10)))
        assertEquals(AnniversaryLine("今天是在一起第 100 天", true), anniversaryLine(start, start.plusDays(99)))
        assertEquals(AnniversaryLine("今天是在一起第 900 天", true), anniversaryLine(start, start.plusDays(899)))
    }

    @Test
    fun `周年前 7 天开始倒数，8 天前还是平常的写法`() {
        assertEquals(AnniversaryLine("还有 7 天是 3 周年", false), anniversaryLine(start, LocalDate.of(2027, 3, 3)))
        assertEquals(AnniversaryLine("还有 1 天是 3 周年", false), anniversaryLine(start, LocalDate.of(2027, 3, 9)))
        assertEquals("在一起第 1088 天", anniversaryLine(start, LocalDate.of(2027, 3, 2))!!.text)
    }

    @Test
    fun `2 月 29 日的纪念日，平年在 2 月 28 日过`() {
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals("今天是在一起 1 周年", anniversaryOn(leap, LocalDate.of(2025, 2, 28)))
        assertEquals("今天是在一起 4 周年", anniversaryOn(leap, LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `没填纪念日不显示；填了以后的日子写还有几天`() {
        assertNull(anniversaryLine(null, start))
        assertEquals(AnniversaryLine("还有 5 天", false), anniversaryLine(start, start.minusDays(5)))
        assertNull(anniversaryOn(start, start))
    }
}
