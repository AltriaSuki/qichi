package app.qichi.server.messages

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Files
import app.qichi.server.db.Messages
import app.qichi.server.db.ilike
import app.qichi.server.db.ReadMarkers
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.tx
import app.qichi.server.files.FileService
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.MessageSearchPage
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.fromWireOrNull
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.util.UuidV7
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.util.UUID

/** 聊天消息（P3-02）：发送、翻历史、撤回、删除进回收站、搜索。 */
class MessageService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writer: RoomWriter,
    private val writes: EntityWrites,
    private val files: FileService,
    private val clock: Clock,
) {
    private val sendable = setOf(MessageKind.Text, MessageKind.Image, MessageKind.File)

    private fun message(id: UUID): Message? = messageQuery().where { Messages.id eq id }.singleOrNull()?.toMessage()

    /**
     * 发消息。同 id 已存在且是自己在这个房间发的 → 返回已有的（created = false），否则 409。
     * 带 replyToId 时由服务端填写原作者和摘要。
     */
    suspend fun send(userId: UUID, roomId: UUID, req: SendMessageRequest): Pair<Message, Boolean> {
        val kind = fromWireOrNull<MessageKind>(req.kind)
        val body = req.body?.trim().orEmpty()
        validate {
            check(kind in sendable, "kind", "只能是 text、image、file")
            check(body.length <= Limits.MESSAGE_BODY_MAX, "body", "最多 ${Limits.MESSAGE_BODY_MAX} 字")
            when (kind) {
                MessageKind.Text -> {
                    check(body.isNotEmpty(), "body", "消息不能为空")
                    check(req.fileId == null, "fileId", "文字消息不带文件")
                }
                MessageKind.Image, MessageKind.File -> check(req.fileId != null, "fileId", "缺少文件")
                else -> Unit
            }
        }
        kind!!
        return db.tx {
            rooms.requireMember(roomId, userId)
            // 锁房间：「id 是否已存在」的判断与插入之间不能有并发写入
            RoomRepository.lockRoom(roomId)
            Messages.select(Messages.roomId, Messages.authorId).where { Messages.id eq req.id }.singleOrNull()?.let { row ->
                if (row[Messages.roomId] != roomId || row[Messages.authorId] != userId) {
                    throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                }
                return@tx message(req.id)!! to false
            }

            req.fileId?.let { fileId ->
                val fileKind = Files.select(Files.kind).where { (Files.id eq fileId) and (Files.roomId eq roomId) }
                    .singleOrNull()?.let { fromWire<FileKind>(it[Files.kind]) }
                validate {
                    when {
                        fileKind == null -> fail("fileId", "文件不存在")
                        kind == MessageKind.Image && fileKind != FileKind.Image -> fail("fileId", "图片消息需要图片文件")
                        kind == MessageKind.File && fileKind !in setOf(FileKind.File, FileKind.Image) -> fail("fileId", "不能作为文件发送")
                    }
                }
            }

            val original = req.replyToId?.let { replyId ->
                message(replyId)?.takeIf { it.roomId == roomId }
                    ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "replyToId：原消息不存在")
            }

            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Message, req.id, userId, now)
            Messages.insert {
                it[id] = req.id
                it[Messages.roomId] = roomId
                it[Messages.seq] = seq
                it[createdSeq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[authorId] = userId
                it[Messages.kind] = kind.wireName
                it[Messages.body] = body
                it[fileId] = req.fileId
                it[replyToId] = original?.id
                it[replyAuthorId] = original?.authorId
                it[replyExcerpt] = original?.let { o ->
                    MessageRules.replyExcerpt(o.kind, o.body, o.file?.fileName, retracted = o.retractedAt != null)
                }
            }
            message(req.id)!! to true
        }
    }

    /** 往上翻历史：createdSeq < beforeSeq，最新的在前。含已删除的（客户端据此隐藏或放进回收站）。 */
    suspend fun history(userId: UUID, roomId: UUID, beforeSeq: Long?, limit: Int): MessagePage {
        validate {
            check(beforeSeq == null || beforeSeq >= 1, "beforeSeq", "必须大于 0")
            check(limit in 1..200, "limit", "1–200")
        }
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val rows = messageQuery()
                .where {
                    val inRoom = Messages.roomId eq roomId
                    if (beforeSeq == null) inRoom else inRoom and (Messages.createdSeq less beforeSeq)
                }
                .orderBy(Messages.createdSeq, SortOrder.DESC)
                .limit(limit + 1)
                .map { it.toMessage() }
            MessagePage(messages = rows.take(limit), hasMore = rows.size > limit)
        }
    }

    /**
     * 撤回（仅作者）：清空正文与附件，保留谁在何时撤回；回复了它的消息的摘要一并清空。
     * 附件文件不再被任何消息引用时从磁盘删除。重复撤回直接返回。
     */
    suspend fun retract(userId: UUID, roomId: UUID, id: UUID): Message {
        val orphanPaths = mutableListOf<String>()
        val result = db.tx {
            rooms.requireMember(roomId, userId)
            val current = message(id)?.takeIf { it.roomId == roomId } ?: notFound()
            if (current.authorId != userId) forbidden("只能撤回自己发的消息")
            if (current.retractedAt != null) return@tx current

            val now = clock.instant()
            writes.update(this, roomId, userId, EntityType.Message, id, Messages) {
                it[Messages.body] = ""
                it[Messages.fileId] = null
                it[Messages.retractedAt] = now
                it[Messages.retractedBy] = userId
            }
            // 回复了它的消息：摘要清空，并各自产生一次变化，让客户端更新
            val replies = Messages.select(Messages.id)
                .where { (Messages.replyToId eq id) and (Messages.roomId eq roomId) }
                .map { it[Messages.id] }
            for (replyId in replies) {
                writes.update(this, roomId, userId, EntityType.Message, replyId, Messages) {
                    it[Messages.replyExcerpt] = null
                }
            }
            current.file?.let { file -> files.releaseIfUnused(file.id)?.let(orphanPaths::add) }
            message(id)!!
        }
        // 事务提交后再删磁盘上的文件（连同缩略图）
        files.deleteStored(orphanPaths)
        return result
    }

    /** 删除进回收站：两位成员都可以删任何消息。已删除的直接返回。 */
    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Message = db.tx {
        rooms.requireMember(roomId, userId)
        val current = message(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Message, id, Messages)
        message(id)!!
    }

    /**
     * 推进自己的未读位置：只进不退（保存 max(旧值, 新值)），不超过房间里最新一条消息。
     * 只同步给本人（SyncService 过滤），不做已读回执。
     */
    suspend fun updateReadMarker(userId: UUID, roomId: UUID, req: UpdateReadMarkerRequest): ReadMarker {
        validate { check(req.lastReadSeq >= 0, "lastReadSeq", "不能小于 0") }
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            val newest = Messages.select(Messages.createdSeq.max()).where { Messages.roomId eq roomId }
                .single()[Messages.createdSeq.max()] ?: 0L
            val target = minOf(req.lastReadSeq, newest)
            val mine = { ReadMarkers.selectAll().where { (ReadMarkers.roomId eq roomId) and (ReadMarkers.userId eq userId) }.singleOrNull()?.toReadMarker() }
            val existing = mine()
            if (existing == null) {
                val id = UuidV7.generate()
                val now = clock.instant()
                val seq = writer.change(this, roomId, EntityType.ReadMarker, id, userId, now)
                ReadMarkers.insert {
                    it[ReadMarkers.id] = id
                    it[ReadMarkers.roomId] = roomId
                    it[ReadMarkers.seq] = seq
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ReadMarkers.userId] = userId
                    it[lastReadSeq] = target
                }
            } else if (target > existing.lastReadSeq) {
                writes.update(this, roomId, userId, EntityType.ReadMarker, existing.id, ReadMarkers) {
                    it[ReadMarkers.lastReadSeq] = target
                }
            }
            mine()!!
        }
    }

    /** 搜索：ILIKE + 三元组索引；不含撤回、删除的。按 createdSeq 降序，游标是上一页最后一条的 createdSeq。 */
    suspend fun search(userId: UUID, roomId: UUID, rawQuery: String?, cursor: String?, limit: Int): MessageSearchPage {
        val query = MessageRules.searchQuery(rawQuery)
        val before = cursor?.toLongOrNull()
        validate {
            check(query != null, "q", "搜索词 1–${Limits.MESSAGE_SEARCH_QUERY_MAX} 字")
            check(cursor == null || (before != null && before > 0), "cursor", "不是有效的游标")
            check(limit in 1..100, "limit", "1–100")
        }
        val pattern = "%" + query!!.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val rows = messageQuery()
                .where {
                    // 与部分索引的条件一致（未删除、未撤回），才能用上索引
                    var cond = (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() and
                        (Messages.body neq "") and (Messages.body ilike pattern)
                    if (before != null) cond = cond and (Messages.createdSeq less before)
                    cond
                }
                .orderBy(Messages.createdSeq, SortOrder.DESC)
                .limit(limit + 1)
                .map { it.toMessage() }
            val page = rows.take(limit)
            MessageSearchPage(messages = page, nextCursor = if (rows.size > limit) page.last().createdSeq.toString() else null)
        }
    }
}
