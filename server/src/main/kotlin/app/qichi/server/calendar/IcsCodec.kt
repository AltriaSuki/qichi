package app.qichi.server.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal

data class IcsEntry(
    val uid: String,
    val title: String,
    val note: String? = null,
    val location: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
) {
    val allDay: Boolean get() = startDate != null
}

/** 只接受可准确映射到栖迟日程的 VEVENT；其他事件留在原文件中。 */
object IcsCodec {
    fun parse(bytes: ByteArray, roomZone: ZoneId): Pair<List<IcsEntry>, Int> {
        val calendar = CalendarBuilder().build(ByteArrayInputStream(bytes))
        val entries = mutableListOf<IcsEntry>()
        var skipped = 0
        for (event in calendar.componentList.all.filterIsInstance<VEvent>()) {
            val entry = runCatching { event.toEntry(roomZone) }.getOrNull()
            if (entry == null) skipped++ else entries += entry
        }
        return entries to skipped
    }

    private fun VEvent.toEntry(roomZone: ZoneId): IcsEntry? {
        if (propertyList.getProperty<net.fortuna.ical4j.model.Property>("RRULE").isPresent ||
            propertyList.getProperty<net.fortuna.ical4j.model.Property>("RECURRENCE-ID").isPresent) return null
        if (propertyList.getProperty<net.fortuna.ical4j.model.Property>("STATUS").orElse(null)?.value == "CANCELLED") return null
        val uid = propertyList.getProperty<Uid>("UID").orElse(null)?.value?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return null
        val title = summary?.value?.trim()?.takeIf { it.length in 1..200 } ?: return null
        val startProperty = propertyList.getProperty<DtStart<*>>("DTSTART").orElse(null) ?: return null
        val start = startProperty.date ?: return null
        val end = propertyList.getProperty<DtEnd<*>>("DTEND").orElse(null)?.date ?: return null
        val note = description?.value?.takeIf { it.length <= 2000 }
        val location = location?.value?.takeIf { it.length <= 200 }
        if (start is LocalDate && end is LocalDate) {
            if (!end.isAfter(start)) return null
            return IcsEntry(uid, title, note, location, startDate = start, endDate = end.minusDays(1))
        }
        if (start is LocalDate || end is LocalDate) return null
        val tzid = startProperty.parameterList.getParameter<Parameter>("TZID").orElse(null)?.value
        val zone = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: roomZone
        val startsAt = start.toInstant(zone) ?: return null
        val endsAt = end.toInstant(zone) ?: return null
        if (!endsAt.isAfter(startsAt)) return null
        return IcsEntry(uid, title, note, location, startsAt = startsAt, endsAt = endsAt)
    }

    private fun Temporal.toInstant(zone: ZoneId): Instant? = when (this) {
        is Instant -> this
        is ZonedDateTime -> toInstant()
        is OffsetDateTime -> toInstant()
        is LocalDateTime -> atZone(zone).toInstant()
        else -> null
    }

    fun write(entries: List<IcsEntry>): ByteArray {
        val calendar = Calendar()
        calendar.propertyList = calendar.propertyList
            .add(ProdId("-//Qichi//Calendar//ZH"))
            .add(Version("2.0", "2.0"))
            .add(CalScale("GREGORIAN"))
        for (entry in entries) {
            val event = if (entry.allDay) {
                VEvent(entry.startDate!!, entry.endDate!!.plusDays(1), entry.title)
            } else {
                VEvent(entry.startsAt!!.atZone(ZoneOffset.UTC), entry.endsAt!!.atZone(ZoneOffset.UTC), entry.title)
            }
            var properties = event.propertyList.add(Uid(entry.uid))
            entry.note?.let { properties = properties.add(Description(it)) }
            entry.location?.let { properties = properties.add(Location(it)) }
            event.propertyList = properties
            calendar.componentList = calendar.componentList.add(event)
        }
        return calendar.toString().toByteArray(Charsets.UTF_8)
    }
}
