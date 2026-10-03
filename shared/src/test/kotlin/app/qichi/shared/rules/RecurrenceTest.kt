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
    fun `每月 31 号：小月取月底，下个大月回到 31 号，不会一直停在 28 号`() {
        val rule = Recurrence.parse("FREQ=MONTHLY")!!
        // 1/31 → 2/28 → 3/31 → 4/30 → 5/31
        val chain = mutableListOf(d("2026-01-31"))
        repeat(4) { chain.add(0, rule.next(chain.first(), Recurrence.monthDayOf(chain))) }
        assertEquals(listOf("2026-05-31", "2026-04-30", "2026-03-31", "2026-02-28", "2026-01-31").map(::d), chain)
        // 闰年 2 月取 29 号
        assertEquals(d("2028-02-29"), rule.next(d("2028-01-31"), 31))
        // 每两个月
        assertEquals(d("2026-03-31"), Recurrence.parse("FREQ=MONTHLY;INTERVAL=2")!!.next(d("2026-01-31"), 31))
    }

    @Test
    fun `本来定在几号：顺延来的月底往前找；本来就是 28 号、中途改了日子的照新的`() {
        assertEquals(31, Recurrence.monthDayOf(listOf(d("2026-02-28"), d("2026-01-31"))))
        assertEquals(30, Recurrence.monthDayOf(listOf(d("2026-02-28"), d("2026-01-30"))))
        assertEquals(28, Recurrence.monthDayOf(listOf(d("2026-02-28"), d("2026-01-28"))))
        assertEquals(28, Recurrence.monthDayOf(listOf(d("2026-02-28"))))
        assertEquals(15, Recurrence.monthDayOf(listOf(d("2026-02-15"), d("2026-01-31"))))
        assertEquals(31, Recurrence.monthDayOf(listOf(d("2026-04-30"), d("2026-03-31"), d("2026-02-28"), d("2026-01-31"))))
        assertNull(Recurrence.monthDayOf(emptyList()))
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
