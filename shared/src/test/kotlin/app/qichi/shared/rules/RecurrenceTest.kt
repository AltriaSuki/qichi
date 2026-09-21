package app.qichi.shared.rules

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecurrenceTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun next(rule: String, after: String) = Recurrence.parse(rule)!!.next(d(after))

    @Test
    fun `每周日：完成后生成下周日`() {
        // 2026-09-27 是周日
        assertEquals(d("2026-10-04"), next("FREQ=WEEKLY;BYDAY=SU", "2026-09-27"))
        assertEquals(d("2026-10-04"), next("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU", "2026-09-27"))
    }

    @Test
    fun `每周多天：同一周里取下一个，周末之后跳到下一周`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        assertEquals(d("2026-09-23"), next(rule, "2026-09-21")) // 周一 → 周三
        assertEquals(d("2026-09-25"), next(rule, "2026-09-23")) // 周三 → 周五
        assertEquals(d("2026-09-28"), next(rule, "2026-09-25")) // 周五 → 下周一
    }

    @Test
    fun `隔周：跳过中间一周`() {
        assertEquals(d("2026-10-05"), next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", "2026-09-21"))
        assertEquals(d("2026-10-05"), next("FREQ=WEEKLY;INTERVAL=2", "2026-09-21"))
    }

    @Test
    fun `每天与每月`() {
        assertEquals(d("2026-09-22"), next("FREQ=DAILY", "2026-09-21"))
        assertEquals(d("2026-09-24"), next("FREQ=DAILY;INTERVAL=3", "2026-09-21"))
        assertEquals(d("2026-10-21"), next("FREQ=MONTHLY", "2026-09-21"))
        assertEquals(d("2026-02-28"), next("FREQ=MONTHLY", "2026-01-31"), "月末顺延到当月最后一天")
    }

    @Test
    fun `不支持的写法返回 null`() {
        listOf("", "FREQ=YEARLY", "FREQ=DAILY;COUNT=3", "FREQ=DAILY;INTERVAL=0", "FREQ=MONTHLY;BYDAY=MO", "FREQ=WEEKLY;BYDAY=XX")
            .forEach { assertNull(Recurrence.parse(it), it) }
    }

    @Test
    fun `格式化后可以解析回来`() {
        val r = Recurrence(Recurrence.Freq.WEEKLY, 2, setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU", r.format())
        assertEquals(r, Recurrence.parse(r.format()))
    }
}
