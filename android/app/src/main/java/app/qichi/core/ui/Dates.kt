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
 * 截止日的说法：今天、明天、昨天；一周内写星期（「周四」）；更远写「09.28」。
 * @return 文字与是否用 Cormorant 数字字体显示
 */
fun relativeDay(date: LocalDate, today: LocalDate): Pair<String, Boolean> {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "今天" to false
        days == 1L -> "明天" to false
        days == -1L -> "昨天" to false
        days in 2..6 -> date.dayOfWeek.chinese to false
        date.year == today.year -> "%02d.%02d".format(date.monthValue, date.dayOfMonth) to true
        else -> "%d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth) to true
    }
}

/** 点分日期：「09.28」；[withYear] 时「2026.09.28」。 */
fun dotDate(date: LocalDate, withYear: Boolean = false): String =
    if (withYear) "%d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth) else "%02d.%02d".format(date.monthValue, date.dayOfMonth)

/**
 * 聊天里的日期分隔：今天、昨天；今年的写「09.18 周三」；更早的带年份「2025.12.31」。
 * @return 文字与是否用等宽数字字体显示
 */
fun chatDay(date: LocalDate, today: LocalDate): Pair<String, Boolean> {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days == 0L -> "今天" to false
        days == 1L -> "昨天" to false
        date.year == today.year -> "%02d.%02d  %s".format(date.monthValue, date.dayOfMonth, date.dayOfWeek.chinese) to true
        else -> "%d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth) to true
    }
}


/**
 * 某个时刻离今天多远的说法：今天只写时刻「19:30」；昨天「昨天 19:30」；更早「09.21 19:30」，不是今年的带年份。
 * 用在只显示「最近一条」的地方（心情），免得上周的内容看起来像刚发生。
 */
fun dayTime(at: Instant, zone: ZoneId, today: LocalDate): String {
    val t = at.atZone(zone)
    val hm = "%02d:%02d".format(t.hour, t.minute)
    val date = t.toLocalDate()
    return when (ChronoUnit.DAYS.between(date, today)) {
        0L -> hm
        1L -> "昨天 $hm"
        else -> "${dotDate(date, withYear = date.year != today.year)} $hm"
    }
}

/** [at] 是不是今天或昨天（房间时区）：心情的「需要安慰」、聊天顶栏的心情只在这两天里显示。 */
fun isRecent(at: Instant, zone: ZoneId, today: LocalDate): Boolean =
    ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), today) in 0L..1L
