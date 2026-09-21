package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateMoodReplyRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/**
 * 心情与回应。所有写操作先落本机（PENDING）再经发件箱发出，离线也能记。
 */
class MoodRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    /** 房间里的心情（不含回收站），最新的在前。 */
    fun observeMoods(roomId: UUID): Flow<List<Local<Mood>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Mood.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Mood>(it) }.sortedByDescending { it.value.createdAt } }

    fun observeReplies(roomId: UUID): Flow<List<Local<MoodReply>>> =
        db.entities().observeByType(roomId.toString(), EntityType.MoodResponse.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<MoodReply>(it) } }

    suspend fun record(roomId: UUID, label: MoodLabel, intensity: Int, note: String?, needsComfort: Boolean): Mood {
        val now = clock.instant()
        val mood = Mood(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now,
            deletedAt = null, deletedBy = null, authorId = me, label = label, intensity = intensity,
            note = note?.trim()?.takeIf { it.isNotEmpty() }, needsComfort = needsComfort,
        )
        store.writeLocal(
            roomId, mood,
            OutboxOp.post("rooms/$roomId/moods", CreateMoodRequest(mood.id, label, intensity, mood.note, needsComfort)),
        )
        scheduler.kickOutbox()
        return mood
    }

    suspend fun delete(mood: Mood) {
        val now = clock.instant()
        store.writeLocal(mood.roomId, mood.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${mood.roomId}/moods/${mood.id}"))
        scheduler.kickOutbox()
    }

    suspend fun respond(mood: Mood, kind: MoodReplyKind) {
        val now = clock.instant()
        val reply = MoodReply(
            id = UuidV7.generate(), roomId = mood.roomId, seq = 0, createdAt = now, updatedAt = now,
            deletedAt = null, deletedBy = null, moodId = mood.id, authorId = me, kind = kind,
        )
        store.writeLocal(
            mood.roomId, reply,
            OutboxOp.post("rooms/${mood.roomId}/moods/${mood.id}/responses", CreateMoodReplyRequest(reply.id, kind)),
        )
        scheduler.kickOutbox()
    }

    suspend fun withdraw(reply: MoodReply) {
        val now = clock.instant()
        store.writeLocal(
            reply.roomId, reply.copy(deletedAt = now, deletedBy = me),
            OutboxOp.delete("rooms/${reply.roomId}/mood-responses/${reply.id}"),
        )
        scheduler.kickOutbox()
    }
}
