package app.qichi.core.ui

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 房间时区里的「今天」。 */
fun todayIn(zone: ZoneId, now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

fun zoneOf(id: String?): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

val DayOfWeek.chinese: String
    get() = when (this) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

/**
 * 截止日的说法：今天、明天、昨天；一周内写星期（「周四」）；更远写「9 · 28」。
 * @return 文字与是否用 Cormorant 数字字体显示
 */
fun relativeDay(date: LocalDate, today: LocalDate): Pair<String, Boolean> {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "今天" to false
        days == 1L -> "明天" to false
        days == -1L -> "昨天" to false
        days in 2..6 -> date.dayOfWeek.chinese to false
        date.year == today.year -> "${date.monthValue} · ${date.dayOfMonth}" to true
        else -> "${date.year} · ${date.monthValue} · ${date.dayOfMonth}" to true
    }
}

/**
 * 聊天里的日期分隔：今天、昨天；今年的写「9 · 18 周三」；更早的带年份。
 * @return 文字与是否用 Cormorant 数字字体显示
 */
fun chatDay(date: LocalDate, today: LocalDate): Pair<String, Boolean> {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days == 0L -> "今天" to false
        days == 1L -> "昨天" to false
        date.year == today.year -> "${date.monthValue} · ${date.dayOfMonth}  ${date.dayOfWeek.chinese}" to true
        else -> "${date.year} · ${date.monthValue} · ${date.dayOfMonth}" to true
    }
}

/** 罗马数字写月份，如 9 月 → ix（设计稿里「3 · ix」这样的写法）。 */
fun monthRoman(month: Int): String = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii")[month - 1]
