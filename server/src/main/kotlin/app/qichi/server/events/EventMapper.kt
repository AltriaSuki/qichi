package app.qichi.server.events

import app.qichi.server.db.Events
import app.qichi.shared.api.Event
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toEvent() = Event(
    id = this[Events.id],
    roomId = this[Events.roomId],
    seq = this[Events.seq],
    createdAt = this[Events.createdAt],
    updatedAt = this[Events.updatedAt],
    deletedAt = this[Events.deletedAt],
    deletedBy = this[Events.deletedBy],
    title = this[Events.title],
    note = this[Events.note],
    location = this[Events.location],
    allDay = this[Events.allDay],
    startsAt = this[Events.startsAt],
    endsAt = this[Events.endsAt],
    startDate = this[Events.startDate],
    endDate = this[Events.endDate],
    participantIds = this[Events.participantIds],
    createdBy = this[Events.createdBy],
    icsUid = this[Events.icsUid],
)
