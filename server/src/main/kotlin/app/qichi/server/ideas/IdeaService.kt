package app.qichi.server.ideas

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Ideas
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.Idea
import app.qichi.shared.api.UpdateIdeaRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toIdea() = Idea(
    id = this[Ideas.id],
    roomId = this[Ideas.roomId],
    seq = this[Ideas.seq],
    createdAt = this[Ideas.createdAt],
    updatedAt = this[Ideas.updatedAt],
    deletedAt = this[Ideas.deletedAt],
    deletedBy = this[Ideas.deletedBy],
    authorId = this[Ideas.authorId],
    body = this[Ideas.body],
)

/** 灵感（P4-09）：随手记。只有作者能改；两个人都能删（进回收站，可恢复）。 */
class IdeaService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun idea(id: UUID): Idea? = Ideas.selectAll().where { Ideas.id eq id }.singleOrNull()?.toIdea()

    private fun checkBody(raw: String): String {
        val body = raw.trim()
        validate { check(body.length in Limits.IDEA_BODY_LENGTH, "body", "灵感 1–${Limits.IDEA_BODY_LENGTH.last} 字") }
        return body
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateIdeaRequest): Pair<Idea, Boolean> {
        val body = checkBody(req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val result = writes.create(this, roomId, userId, EntityType.Idea, req.id, Ideas, ::idea) {
                it[Ideas.authorId] = userId
                it[Ideas.body] = body
            }
            // 同一个 id 已经是对方记下的：不是重试，而是 id 冲突
            if (!result.second && result.first.authorId != userId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            result
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateIdeaRequest): Idea {
        val body = checkBody(req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = idea(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
            if (current.authorId != userId) forbidden("只能修改自己记下的灵感")
            if (current.body != body) writes.update(this, roomId, userId, EntityType.Idea, id, Ideas) { it[Ideas.body] = body }
            idea(id)!!
        }
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Idea = db.tx {
        rooms.requireMember(roomId, userId)
        val current = idea(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Idea, id, Ideas)
        idea(id)!!
    }
}
