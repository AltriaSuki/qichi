package app.qichi.feature.timeline

import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.model.TimelineEntryKind
import java.time.LocalDate
import java.time.ZoneId

/** 时间线上的一天：心情放最上面，照片单独一排，其余按时间先后。 */
data class TimelineDay(
    val date: LocalDate,
    val moods: List<TimelineEntry>,
    val photos: List<TimelineEntry>,
    val others: List<TimelineEntry>,
)

/** 按房间时区的日期分组，新的一天在前；一天里心情、照片、其余各自按时间先后。 */
fun timelineDays(entries: List<TimelineEntry>, zone: ZoneId): List<TimelineDay> =
    entries.groupBy { it.at.atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (date, list) ->
            val sorted = list.sortedBy { it.at }
            TimelineDay(
                date,
                moods = sorted.filter { it.kind == TimelineEntryKind.Mood },
                photos = sorted.filter { it.kind == TimelineEntryKind.Photo },
                others = sorted.filter { it.kind != TimelineEntryKind.Mood && it.kind != TimelineEntryKind.Photo },
            )
        }
