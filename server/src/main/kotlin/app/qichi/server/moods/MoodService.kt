package app.qichi.server.moods

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateMoodReplyRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** 心情与回应（P2-01）。回应只是低压力的陪伴，不产生任何待办或提醒数字。 */
class MoodService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun mood(id: UUID): Mood? = Moods.selectAll().where { Moods.id eq id }.singleOrNull()?.toMood()
    private fun reply(id: UUID): MoodReply? = MoodResponses.selectAll().where { MoodResponses.id eq id }.singleOrNull()?.toMoodReply()

    suspend fun create(userId: UUID, roomId: UUID, req: CreateMoodRequest): Pair<Mood, Boolean> {
        val note = req.note?.trim()?.takeIf { it.isNotEmpty() }
        validate {
            check(req.intensity in Limits.MOOD_INTENSITY, "intensity", "强度必须在 1 到 10 之间")
            check((note?.length ?: 0) <= Limits.MOOD_NOTE_MAX, "note", "备注最多 ${Limits.MOOD_NOTE_MAX} 字")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            writes.create(this, roomId, userId, EntityType.Mood, req.id, Moods, ::mood) {
                it[Moods.authorId] = userId
                it[Moods.label] = req.label.wireName
                it[Moods.intensity] = req.intensity.toShort()
                it[Moods.note] = note
                it[Moods.needsComfort] = req.needsComfort
            }
        }
    }

    /** 删除自己的心情（进回收站）。 */
    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Mood = db.tx {
        rooms.requireMember(roomId, userId)
        val current = mood(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.authorId != userId) forbidden("只能删除自己的心情")
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Mood, id, Moods)
        mood(id)!!
    }

    /** 回应对方的心情；同一种回应同时只保留一条有效的。 */
    suspend fun respond(userId: UUID, roomId: UUID, moodId: UUID, req: CreateMoodReplyRequest): Pair<MoodReply, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        // 先锁房间：下面「同一种回应是否已存在」的判断与插入之间不能有并发写入
        RoomRepository.lockRoom(roomId)
        reply(req.id)?.let { existing ->
            if (existing.roomId != roomId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            return@tx existing to false
        }
        val target = mood(moodId)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
        if (target.authorId == userId) forbidden("不能回应自己的心情")
        val same = MoodResponses.selectAll().where {
            (MoodResponses.moodId eq moodId) and (MoodResponses.authorId eq userId) and
                (MoodResponses.kind eq req.kind.wireName) and MoodResponses.deletedAt.isNull()
        }.singleOrNull()?.toMoodReply()
        if (same != null) return@tx same to false

        writes.create(this, roomId, userId, EntityType.MoodResponse, req.id, MoodResponses, ::reply) {
            it[MoodResponses.moodId] = moodId
            it[MoodResponses.authorId] = userId
            it[MoodResponses.kind] = req.kind.wireName
        }
    }

    /** 收回自己的回应。 */
    suspend fun withdraw(userId: UUID, roomId: UUID, id: UUID): MoodReply = db.tx {
        rooms.requireMember(roomId, userId)
        val current = reply(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.authorId != userId) forbidden("只能收回自己的回应")
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.MoodResponse, id, MoodResponses)
        reply(id)!!
    }
}
