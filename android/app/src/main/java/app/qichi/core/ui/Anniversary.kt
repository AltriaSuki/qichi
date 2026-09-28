package app.qichi.core.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 纪念日在今天页上的那一句（P16-02）。纪念日当天是「第 1 天」；按房间时区的今天算。
 * - 周年当天：「今天是在一起 2 周年」；整百天当天：「今天是在一起第 300 天」（[special] 为真，写得醒目些）
 * - 周年前 7 天起：「还有 3 天是 2 周年」
 * - 平常：「在一起第 N 天」；纪念日还没到（填了以后的日子）：「还有 N 天」
 */
data class AnniversaryLine(val text: String, val special: Boolean)

/** 在一起第几天（纪念日当天是 1）；纪念日还没到时是 0 或负数。 */
fun daysTogether(anniversary: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(anniversary, today) + 1

/** [date] 是不是周年或整百天（值得提醒的日子）；是的话返回那句话。 */
fun anniversaryOn(anniversary: LocalDate, date: LocalDate): String? {
    val days = daysTogether(anniversary, date)
    if (days <= 1) return null
    // 按年份差算：2 月 29 日的纪念日在平年落到 2 月 28 日（plusYears 的规则）
    val years = (date.year - anniversary.year).toLong()
    if (years >= 1 && anniversary.plusYears(years) == date) return "今天是在一起 $years 周年"
    if (days % 100 == 0L) return "今天是在一起第 $days 天"
    return null
}

fun anniversaryLine(anniversary: LocalDate?, today: LocalDate): AnniversaryLine? {
    anniversary ?: return null
    val days = daysTogether(anniversary, today)
    if (days <= 0) return AnniversaryLine("还有 ${1 - days} 天", special = false)
    anniversaryOn(anniversary, today)?.let { return AnniversaryLine(it, special = true) }
    val thisYear = (today.year - anniversary.year).toLong()
    val nextYears = if (anniversary.plusYears(thisYear).isAfter(today)) thisYear else thisYear + 1
    val next = anniversary.plusYears(nextYears)
    val until = ChronoUnit.DAYS.between(today, next)
    if (until in 1..7) return AnniversaryLine("还有 $until 天是 $nextYears 周年", special = false)
    return AnniversaryLine("在一起第 $days 天", special = false)
}
