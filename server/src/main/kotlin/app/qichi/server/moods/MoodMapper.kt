package app.qichi.server.moods

import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toMood() = Mood(
    id = this[Moods.id],
    roomId = this[Moods.roomId],
    seq = this[Moods.seq],
    createdAt = this[Moods.createdAt],
    updatedAt = this[Moods.updatedAt],
    deletedAt = this[Moods.deletedAt],
    deletedBy = this[Moods.deletedBy],
    authorId = this[Moods.authorId],
    label = fromWire<MoodLabel>(this[Moods.label]),
    intensity = this[Moods.intensity].toInt(),
    note = this[Moods.note],
    needsComfort = this[Moods.needsComfort],
)

fun ResultRow.toMoodReply() = MoodReply(
    id = this[MoodResponses.id],
    roomId = this[MoodResponses.roomId],
    seq = this[MoodResponses.seq],
    createdAt = this[MoodResponses.createdAt],
    updatedAt = this[MoodResponses.updatedAt],
    deletedAt = this[MoodResponses.deletedAt],
    deletedBy = this[MoodResponses.deletedBy],
    moodId = this[MoodResponses.moodId],
    authorId = this[MoodResponses.authorId],
    kind = fromWire<MoodReplyKind>(this[MoodResponses.kind]),
)
