package app.qichi.feature.writing

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** AI 起草稿能选的时间范围（按房间时区的「今天」算，都不超过 31 天）。 */
object DraftRanges {
    data class Range(val label: String, val start: LocalDate, val end: LocalDate)

    fun presets(today: LocalDate): List<Range> {
        // 最近的一个完整周末：今天是周六周日就算这个周末（到今天为止）
        val weekend = when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> Range("这个周末", today, today)
            DayOfWeek.SUNDAY -> Range("这个周末", today.minusDays(1), today)
            else -> today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY)).let { Range("上个周末", it, it.plusDays(1)) }
        }
        val lastMonth = today.minusMonths(1)
        return listOf(
            Range("最近一周", today.minusDays(6), today),
            weekend,
            Range("这个月", today.withDayOfMonth(1), today),
            Range("上个月", lastMonth.withDayOfMonth(1), lastMonth.with(TemporalAdjusters.lastDayOfMonth())),
        )
    }
}
