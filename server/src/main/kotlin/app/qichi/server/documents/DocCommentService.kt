package app.qichi.server.documents

import app.qichi.server.db.DocComments
import app.qichi.server.db.Documents
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateDocCommentRequest
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.UpdateDocCommentRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toDocComment() = DocComment(
    id = this[DocComments.id], roomId = this[DocComments.roomId], seq = this[DocComments.seq],
    createdAt = this[DocComments.createdAt], updatedAt = this[DocComments.updatedAt],
    deletedAt = this[DocComments.deletedAt], deletedBy = this[DocComments.deletedBy],
    documentId = this[DocComments.documentId], parentId = this[DocComments.parentId], authorId = this[DocComments.authorId],
    body = this[DocComments.body], quote = this[DocComments.quote], version = this[DocComments.version],
    resolvedAt = this[DocComments.resolvedAt], resolvedBy = this[DocComments.resolvedBy],
)

/**
 * 文稿段落旁的留言（P9-03）。两位成员都能留言、回复、解决 / 重新打开；正文只有作者能改，
 * 只能删自己写的讨论开头（进回收站），回复不能单独删。文稿删了留言跟着看不到（回收站里的文稿恢复后回来）。
 */
class DocCommentService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun comment(id: UUID): DocComment? = DocComments.selectAll().where { DocComments.id eq id }.singleOrNull()?.toDocComment()

    private fun liveComment(roomId: UUID, id: UUID): DocComment {
        val c = comment(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
        liveDocument(roomId, c.documentId)
        return c
    }

    /** 本房间里、没被删除的文稿；否则 404。 */
    private fun liveDocument(roomId: UUID, id: UUID) {
        val row = Documents.select(Documents.roomId, Documents.deletedAt).where { Documents.id eq id }.singleOrNull()
        if (row == null || row[Documents.roomId] != roomId || row[Documents.deletedAt] != null) notFound()
    }

    private fun cleanBody(raw: String): String {
        val t = raw.trim()
        validate { check(t.length in Limits.DOC_COMMENT_LENGTH, "body", "留言 1–${Limits.DOC_COMMENT_LENGTH.last} 字") }
        return t
    }

    suspend fun create(userId: UUID, roomId: UUID, documentId: UUID, req: CreateDocCommentRequest): Pair<DocComment, Boolean> {
        val body = cleanBody(req.body)
        val quote = req.quote?.replace(Regex("\\s+"), " ")?.trim()?.take(Limits.DOC_COMMENT_QUOTE_MAX)?.ifEmpty { null }
        validate {
            if (req.parentId == null) check(quote != null, "quote", "留言要钉在一段原文上") else check(req.quote == null, "quote", "回复不用钉原文")
            check((req.version ?: 0) >= 0, "version", "版本号不对")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            comment(req.id)?.let { existing ->
                if (existing.roomId != roomId || existing.authorId != userId || existing.documentId != documentId) {
                    throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                }
                return@tx existing to false
            }
            liveDocument(roomId, documentId)
            req.parentId?.let { parentId ->
                val parent = liveComment(roomId, parentId)
                validate {
                    check(parent.documentId == documentId, "parentId", "回复的留言不在这篇文稿里")
                    check(parent.parentId == null, "parentId", "只能回复讨论的开头")
                }
            }
            writes.create(this, roomId, userId, EntityType.DocComment, req.id, DocComments, ::comment) {
                it[DocComments.documentId] = documentId
                it[DocComments.parentId] = req.parentId
                it[DocComments.authorId] = userId
                it[DocComments.body] = body
                it[DocComments.quote] = if (req.parentId == null) quote else null
                it[DocComments.version] = if (req.parentId == null) req.version else null
            }
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateDocCommentRequest): DocComment {
        val body = cleanBody(req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val c = liveComment(roomId, id)
            if (c.authorId != userId) forbidden("只能改自己写的留言")
            if (c.body != body) writes.update(this, roomId, userId, EntityType.DocComment, id, DocComments) { it[DocComments.body] = body }
            comment(id)!!
        }
    }

    suspend fun setResolved(userId: UUID, roomId: UUID, id: UUID, resolved: Boolean): DocComment = db.tx {
        rooms.requireMember(roomId, userId)
        val c = liveComment(roomId, id)
        validate { check(c.parentId == null, "id", "只有讨论的开头能标为解决") }
        if ((c.resolvedAt != null) != resolved) {
            writes.update(this, roomId, userId, EntityType.DocComment, id, DocComments) {
                it[DocComments.resolvedAt] = if (resolved) writes.now() else null
                it[DocComments.resolvedBy] = if (resolved) userId else null
            }
        }
        comment(id)!!
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): DocComment = db.tx {
        rooms.requireMember(roomId, userId)
        val c = liveComment(roomId, id)
        if (c.authorId != userId) forbidden("只能删自己写的留言")
        validate { check(c.parentId == null, "id", "回复不能单独删") }
        writes.softDelete(this, roomId, userId, EntityType.DocComment, id, DocComments)
        comment(id)!!
    }
}
