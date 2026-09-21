package app.qichi.server.messages

import app.qichi.server.db.Files
import app.qichi.server.db.Messages
import app.qichi.server.db.ReadMarkers
import app.qichi.server.files.toFileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.selectAll

/** 消息查询：左连接文件表，带出附件元数据。 */
fun messageQuery(): Query = Messages.join(Files, JoinType.LEFT, Messages.fileId, Files.id).selectAll()

/** 用 [messageQuery] 查出的行转成 Message。 */
fun ResultRow.toMessage() = Message(
    id = this[Messages.id],
    roomId = this[Messages.roomId],
    seq = this[Messages.seq],
    createdAt = this[Messages.createdAt],
    updatedAt = this[Messages.updatedAt],
    deletedAt = this[Messages.deletedAt],
    deletedBy = this[Messages.deletedBy],
    authorId = this[Messages.authorId],
    kind = fromWire<MessageKind>(this[Messages.kind]),
    body = this[Messages.body],
    file = if (this[Messages.fileId] != null && getOrNull(Files.id) != null) toFileMeta() else null,
    replyToId = this[Messages.replyToId],
    replyAuthorId = this[Messages.replyAuthorId],
    replyExcerpt = this[Messages.replyExcerpt],
    retractedAt = this[Messages.retractedAt],
    retractedBy = this[Messages.retractedBy],
    createdSeq = this[Messages.createdSeq],
)

fun ResultRow.toReadMarker() = ReadMarker(
    id = this[ReadMarkers.id],
    roomId = this[ReadMarkers.roomId],
    seq = this[ReadMarkers.seq],
    createdAt = this[ReadMarkers.createdAt],
    updatedAt = this[ReadMarkers.updatedAt],
    deletedAt = this[ReadMarkers.deletedAt],
    deletedBy = this[ReadMarkers.deletedBy],
    userId = this[ReadMarkers.userId],
    lastReadSeq = this[ReadMarkers.lastReadSeq],
)
