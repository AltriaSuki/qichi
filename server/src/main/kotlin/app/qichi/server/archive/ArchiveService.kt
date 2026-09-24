package app.qichi.server.archive

import app.qichi.server.db.Tx
import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.ArchiveRevisions
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Messages
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.ArchiveRevision
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.ReviseArchiveItemRequest
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toArchiveItem() = ArchiveItem(
    id = this[ArchiveItems.id], roomId = this[ArchiveItems.roomId], seq = this[ArchiveItems.seq],
    createdAt = this[ArchiveItems.createdAt], updatedAt = this[ArchiveItems.updatedAt],
    deletedAt = this[ArchiveItems.deletedAt], deletedBy = this[ArchiveItems.deletedBy],
    kind = fromWire<ArchiveKind>(this[ArchiveItems.kind]), title = this[ArchiveItems.title], body = this[ArchiveItems.body],
    createdBy = this[ArchiveItems.createdBy], currentRevision = this[ArchiveItems.currentRevision],
    revisedBy = this[ArchiveItems.revisedBy], sourceMessageId = this[ArchiveItems.sourceMessageId],
)

private fun ResultRow.toRevision() = ArchiveRevision(
    id = this[ArchiveRevisions.id], itemId = this[ArchiveRevisions.itemId], revision = this[ArchiveRevisions.revision],
    authorId = this[ArchiveRevisions.authorId], title = this[ArchiveRevisions.title], body = this[ArchiveRevisions.body],
    sourceMessageId = this[ArchiveRevisions.sourceMessageId], createdAt = this[ArchiveRevisions.createdAt],
)

/**
 * 档案（P6-01）：长期共同事实。每次修改都是一次不可变的修订（带基线，落后 409），条目上保存当前标题与正文。
 * 可以追到来源消息（必须是这个房间里、没被删除的消息）。两位成员都能修订、删除（进回收站）。
 */
class ArchiveService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun item(id: UUID): ArchiveItem? = ArchiveItems.selectAll().where { ArchiveItems.id eq id }.singleOrNull()?.toArchiveItem()
    private fun liveItem(roomId: UUID, id: UUID) = item(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()

    private fun check(rawTitle: String, rawBody: String): Pair<String, String> {
        val title = rawTitle.trim()
        val body = rawBody.trim()
        validate {
            check(title.length in Limits.ARCHIVE_TITLE_LENGTH, "title", "标题 1–${Limits.ARCHIVE_TITLE_LENGTH.last} 字")
            check(body.length <= Limits.ARCHIVE_BODY_MAX, "body", "正文最多 ${Limits.ARCHIVE_BODY_MAX} 字")
        }
        return title to body
    }

    /** 来源消息必须在这个房间里、没被删除。 */
    private fun checkSource(roomId: UUID, messageId: UUID?) {
        if (messageId == null) return
        val ok = Messages.select(Messages.id).where { (Messages.id eq messageId) and (Messages.roomId eq roomId) and Messages.deletedAt.isNull() }.any()
        validate { check(ok, "sourceMessageId", "来源消息不存在") }
    }

    suspend fun list(userId: UUID, roomId: UUID): List<ArchiveItem> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }
            .orderBy(ArchiveItems.updatedAt, SortOrder.DESC).map { it.toArchiveItem() }
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateArchiveItemRequest): Pair<ArchiveItem, Boolean> {
        check(req.title, req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            createIn(this, userId, roomId, req)
        }
    }

    /** 在已有事务里建（接受 AI 提议时和提议的状态一起提交）；成员身份由调用方检查。 */
    fun createIn(tx: Tx, userId: UUID, roomId: UUID, req: CreateArchiveItemRequest): Pair<ArchiveItem, Boolean> {
        val (title, body) = check(req.title, req.body)
        checkSource(roomId, req.sourceMessageId)
        val result = writes.create(tx, roomId, userId, EntityType.ArchiveItem, req.id, ArchiveItems, ::item) {
            it[ArchiveItems.kind] = req.kind.wireName
            it[ArchiveItems.title] = title
            it[ArchiveItems.body] = body
            it[ArchiveItems.createdBy] = userId
            it[ArchiveItems.currentRevision] = 1
            it[ArchiveItems.revisedBy] = userId
            it[ArchiveItems.sourceMessageId] = req.sourceMessageId
        }
        if (result.second) {
            // 第 1 次修订与条目同一个 id（条目 id 本身就不会重复）
            ArchiveRevisions.insert {
                it[id] = req.id
                it[itemId] = req.id
                it[revision] = 1
                it[authorId] = userId
                it[ArchiveRevisions.title] = title
                it[ArchiveRevisions.body] = body
                it[sourceMessageId] = req.sourceMessageId
                it[createdAt] = result.first.createdAt
            }
        }
        return result
    }

    suspend fun revise(userId: UUID, roomId: UUID, id: UUID, req: ReviseArchiveItemRequest): Pair<ArchiveItem, Boolean> {
        val (title, body) = check(req.title, req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            ArchiveRevisions.selectAll().where { ArchiveRevisions.id eq req.id }.singleOrNull()?.toRevision()?.let { existing ->
                if (existing.itemId != id || existing.authorId != userId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                return@tx liveItem(roomId, id) to false
            }
            val current = liveItem(roomId, id)
            if (req.baseRevision != current.currentRevision) {
                throw ApiException(
                    ProblemCode.ConflictVersion, "档案已经被修订过",
                    detail = "当前是第 ${current.currentRevision} 次修订，你基于第 ${req.baseRevision} 次",
                    latestVersion = current.currentRevision,
                )
            }
            checkSource(roomId, req.sourceMessageId)
            val next = current.currentRevision + 1
            ArchiveRevisions.insert {
                it[ArchiveRevisions.id] = req.id
                it[itemId] = id
                it[revision] = next
                it[authorId] = userId
                it[ArchiveRevisions.title] = title
                it[ArchiveRevisions.body] = body
                it[sourceMessageId] = req.sourceMessageId
                it[createdAt] = writes.now()
            }
            writes.update(this, roomId, userId, EntityType.ArchiveItem, id, ArchiveItems) {
                it[ArchiveItems.title] = title
                it[ArchiveItems.body] = body
                it[ArchiveItems.currentRevision] = next
                it[ArchiveItems.revisedBy] = userId
                it[ArchiveItems.sourceMessageId] = req.sourceMessageId
            }
            item(id)!! to true
        }
    }

    suspend fun revisions(userId: UUID, roomId: UUID, id: UUID): List<ArchiveRevision> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        liveItem(roomId, id)
        ArchiveRevisions.selectAll().where { ArchiveRevisions.itemId eq id }
            .orderBy(ArchiveRevisions.revision, SortOrder.DESC).map { it.toRevision() }
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): ArchiveItem = db.tx {
        rooms.requireMember(roomId, userId)
        val current = item(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.ArchiveItem, id, ArchiveItems)
        item(id)!!
    }
}
