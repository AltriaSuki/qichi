package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.Event
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateEventRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 日程的本机读写：先写本机再经发件箱发出。 */
class EventRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    fun observeEvents(roomId: UUID): Flow<List<Local<Event>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Event.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Event>(it) } }

    suspend fun create(roomId: UUID, draft: EventDraft): Event {
        val now = clock.instant()
        val event = Event(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            title = draft.title.trim(), note = draft.note.trim().ifEmpty { null }, location = draft.location.trim().ifEmpty { null },
            allDay = draft.allDay, startsAt = draft.startsAt, endsAt = draft.endsAt, startDate = draft.startDate, endDate = draft.endDate,
            participantIds = draft.participantIds, createdBy = me, icsUid = null,
        )
        store.writeLocal(
            roomId, event,
            OutboxOp.post(
                "rooms/$roomId/events",
                CreateEventRequest(
                    event.id, event.title, event.allDay, event.note, event.location,
                    event.startsAt, event.endsAt, event.startDate, event.endDate, event.participantIds,
                ),
            ),
        )
        scheduler.kickOutbox()
        return event
    }

    /** 保存修改：整份时间字段一起发（服务端校验最终形态），其余只发改动的。 */
    suspend fun update(event: Event, draft: EventDraft) {
        val updated = event.copy(
            title = draft.title.trim(), note = draft.note.trim().ifEmpty { null }, location = draft.location.trim().ifEmpty { null },
            allDay = draft.allDay, startsAt = draft.startsAt, endsAt = draft.endsAt, startDate = draft.startDate, endDate = draft.endDate,
            participantIds = draft.participantIds, updatedAt = clock.instant(),
        )
        val change = UpdateEventRequest(
            title = if (updated.title != event.title) Patch.of(updated.title) else Patch.Absent,
            note = if (updated.note != event.note) Patch.of(updated.note) else Patch.Absent,
            location = if (updated.location != event.location) Patch.of(updated.location) else Patch.Absent,
            allDay = Patch.of(updated.allDay),
            startsAt = Patch.of(updated.startsAt),
            endsAt = Patch.of(updated.endsAt),
            startDate = Patch.of(updated.startDate),
            endDate = Patch.of(updated.endDate),
            participantIds = if (updated.participantIds != event.participantIds) Patch.of(updated.participantIds) else Patch.Absent,
        )
        store.writeLocal(event.roomId, updated, OutboxOp.patch("rooms/${event.roomId}/events/${event.id}", change))
        scheduler.kickOutbox()
    }

    suspend fun delete(event: Event) {
        val now = clock.instant()
        store.writeLocal(event.roomId, event.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${event.roomId}/events/${event.id}"))
        scheduler.kickOutbox()
    }
}

/** 日程编辑的内容（时间已换算成 Instant / 日期）。 */
data class EventDraft(
    val title: String,
    val allDay: Boolean,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val location: String = "",
    val note: String = "",
    val participantIds: List<UUID> = emptyList(),
)

/** 日程覆盖房间时区里的哪些日子（跨天的日程在每一天都出现）。 */
fun Event.days(zone: ZoneId): List<LocalDate> {
    val start = if (allDay) startDate else startsAt?.atZone(zone)?.toLocalDate()
    val end = if (allDay) endDate else endsAt?.atZone(zone)?.toLocalDate()
    if (start == null || end == null || end.isBefore(start)) return emptyList()
    return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.take(62).toList()
}
