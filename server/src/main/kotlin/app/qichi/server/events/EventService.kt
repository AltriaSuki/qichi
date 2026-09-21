package app.qichi.server.events

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Events
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.Validator
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.Event
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateEventRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 日程（P2-03）。定时日程用 startsAt / endsAt；全天日程用 startDate / endDate（含首尾，按房间时区）。
 */
class EventService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun event(id: UUID): Event? = Events.selectAll().where { Events.id eq id }.singleOrNull()?.toEvent()

    /** 校验后的一份完整日程内容。 */
    private data class Shape(
        val title: String,
        val note: String?,
        val location: String?,
        val allDay: Boolean,
        val startsAt: Instant?,
        val endsAt: Instant?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val participantIds: List<UUID>,
    )

    private fun Validator.checkShape(s: Shape, roomId: UUID) {
        check(s.title.length in Limits.EVENT_TITLE_LENGTH, "title", "标题 1–200 个字")
        check((s.note?.length ?: 0) <= Limits.NOTE_MAX, "note", "备注最多 ${Limits.NOTE_MAX} 字")
        check((s.location?.length ?: 0) <= Limits.EVENT_LOCATION_MAX, "location", "地点最多 ${Limits.EVENT_LOCATION_MAX} 字")
        if (s.allDay) {
            check(s.startDate != null && s.endDate != null, "startDate", "全天日程需要开始和结束日期")
            check(s.startsAt == null && s.endsAt == null, "startsAt", "全天日程不用时刻")
            if (s.startDate != null && s.endDate != null) check(!s.endDate.isBefore(s.startDate), "endDate", "结束不能早于开始")
        } else {
            check(s.startsAt != null && s.endsAt != null, "startsAt", "需要开始和结束时刻")
            check(s.startDate == null && s.endDate == null, "startDate", "定时日程不用日期字段")
            if (s.startsAt != null && s.endsAt != null) check(!s.endsAt.isBefore(s.startsAt), "endsAt", "结束不能早于开始")
        }
        check(s.participantIds.all { RoomRepository.isMember(roomId, it) }, "participantIds", "参与者必须是房间里的人")
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateEventRequest): Pair<Event, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        val shape = Shape(
            title = req.title.trim(),
            note = req.note?.trim()?.takeIf { it.isNotEmpty() },
            location = req.location?.trim()?.takeIf { it.isNotEmpty() },
            allDay = req.allDay,
            startsAt = req.startsAt, endsAt = req.endsAt, startDate = req.startDate, endDate = req.endDate,
            participantIds = req.participantIds.distinct(),
        )
        validate { checkShape(shape, roomId) }
        writes.create(this, roomId, userId, EntityType.Event, req.id, Events, ::event) {
            it[Events.title] = shape.title
            it[Events.note] = shape.note
            it[Events.location] = shape.location
            it[Events.allDay] = shape.allDay
            it[Events.startsAt] = shape.startsAt
            it[Events.endsAt] = shape.endsAt
            it[Events.startDate] = shape.startDate
            it[Events.endDate] = shape.endDate
            it[Events.participantIds] = shape.participantIds
            it[Events.createdBy] = userId
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateEventRequest): Event = db.tx {
        rooms.requireMember(roomId, userId)
        val current = event(id)?.takeIf { it.roomId == roomId } ?: notFound()
        fun <T> Patch<T>.or(value: T): T = if (this is Patch.Value) this.value else value
        val shape = Shape(
            title = req.title.or(current.title).trim(),
            note = req.note.or(current.note)?.trim()?.takeIf { it.isNotEmpty() },
            location = req.location.or(current.location)?.trim()?.takeIf { it.isNotEmpty() },
            allDay = req.allDay.or(current.allDay),
            startsAt = req.startsAt.or(current.startsAt),
            endsAt = req.endsAt.or(current.endsAt),
            startDate = req.startDate.or(current.startDate),
            endDate = req.endDate.or(current.endDate),
            participantIds = req.participantIds.or(current.participantIds).distinct(),
        )
        validate { checkShape(shape, roomId) }
        writes.update(this, roomId, userId, EntityType.Event, id, Events) {
            it[Events.title] = shape.title
            it[Events.note] = shape.note
            it[Events.location] = shape.location
            it[Events.allDay] = shape.allDay
            it[Events.startsAt] = shape.startsAt
            it[Events.endsAt] = shape.endsAt
            it[Events.startDate] = shape.startDate
            it[Events.endDate] = shape.endDate
            it[Events.participantIds] = shape.participantIds
        }
        event(id)!!
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Event = db.tx {
        rooms.requireMember(roomId, userId)
        val current = event(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Event, id, Events)
        event(id)!!
    }
}
