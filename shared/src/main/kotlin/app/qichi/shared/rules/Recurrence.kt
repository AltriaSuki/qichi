package app.qichi.shared.rules

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 重复待办的规则（RRULE 的一个子集）：`FREQ=DAILY|WEEKLY|MONTHLY`、`INTERVAL=n`、`BYDAY=MO,TU,…`（只用于 WEEKLY）。
 * 例：`FREQ=WEEKLY;BYDAY=SU`（每周日）、`FREQ=DAILY;INTERVAL=2`（每两天）、`FREQ=MONTHLY`（每月同一天）。
 */
data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY }

    /**
     * 某一次截止日之后的下一次截止日。
     * @param monthDay 每月重复时本来定的是几号（见 [monthDayOf]）：31 号的在小月取月底，下个大月再回到 31 号；
     *   不给就按 [after] 的日子（2 月 28 日之后一直是 28 号）
     */
    fun next(after: LocalDate, monthDay: Int? = null): LocalDate = when (freq) {
        Freq.DAILY -> after.plusDays(interval.toLong())
        Freq.MONTHLY -> after.plusMonths(interval.toLong()).let { target ->
            val day = monthDay ?: after.dayOfMonth
            target.withDayOfMonth(minOf(day, target.lengthOfMonth()))
        }
        Freq.WEEKLY -> if (byDay.isEmpty()) {
            after.plusWeeks(interval.toLong())
        } else {
            // 同一周里后面还有选中的日子就用它；否则跳 interval 周，取那一周最早选中的日子
            val sameWeek = byDay.map { after.with(TemporalAdjusters.nextOrSame(it)) }
                .filter { it.isAfter(after) && ChronoUnit.DAYS.between(weekStart(after), it) < 7 }
                .minOrNull()
            sameWeek ?: run {
                val targetWeek = weekStart(after).plusWeeks(interval.toLong())
                byDay.map { targetWeek.with(TemporalAdjusters.nextOrSame(it)) }.min()
            }
        }
    }

    fun format(): String = buildList {
        add("FREQ=$freq")
        if (interval != 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.sorted().joinToString(",") { CODES.getValue(it) })
    }.joinToString(";")

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private val DAYS = CODES.entries.associate { (k, v) -> v to k }

        private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /**
         * 每月重复的待办本来定在几号。[dueDays] 是这一次和之前几次的截止日（最近的在前）：
         * 这一次落在月底时可能是小月顺延来的（31 号 → 2 月 28 日），往前找到不是顺延来的那一次。
         * 例：1/31 → 2/28 → 下一次是 3/31；1/28 → 2/28 → 下一次是 3/28；中途改成 15 号就按 15 号。
         */
        fun monthDayOf(dueDays: List<LocalDate>): Int? {
            var day = dueDays.firstOrNull() ?: return null
            for (earlier in dueDays.drop(1)) {
                if (day.dayOfMonth != day.lengthOfMonth() || earlier.dayOfMonth <= day.dayOfMonth) break
                day = earlier
            }
            return day.dayOfMonth
        }

        /** 解析失败（不支持的写法）返回 null。 */
        fun parse(rule: String): Recurrence? {
            val parts = rule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }
                .associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "").uppercase() }
            if (parts.keys.any { it !in setOf("FREQ", "INTERVAL", "BYDAY") }) return null
            val freq = runCatching { Freq.valueOf(parts["FREQ"] ?: return null) }.getOrNull() ?: return null
            val interval = parts["INTERVAL"]?.let { it.toIntOrNull() ?: return null } ?: 1
            if (interval !in 1..365) return null
            val byDay = parts["BYDAY"]?.split(',')?.map { DAYS[it.trim()] ?: return null }?.toSet() ?: emptySet()
            if (byDay.isNotEmpty() && freq != Freq.WEEKLY) return null
            return Recurrence(freq, interval, byDay)
        }
    }
}
