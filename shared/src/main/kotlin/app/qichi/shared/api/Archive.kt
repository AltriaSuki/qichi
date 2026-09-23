package app.qichi.shared.api

import app.qichi.shared.model.ArchiveKind
import kotlinx.serialization.Serializable

/**
 * 同步实体 archive_item：一条长期共同事实（偏好、共识、决定、边界、担忧、里程碑）。
 * 列表与离线阅读直接用这里的当前标题与正文；历次修订不可变，按需另取。
 */
@Serializable
data class ArchiveItem(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val kind: ArchiveKind,
    val title: String,
    val body: String,
    val createdBy: Id,
    /** 当前修订号，从 1 开始 */
    val currentRevision: Int,
    /** 当前修订的作者 */
    val revisedBy: Id,
    /** 当前修订依据的聊天消息（可以没有；消息被彻底删除后为空） */
    val sourceMessageId: Id?,
) : SyncEntity

@Serializable
data class CreateArchiveItemRequest(
    val id: Id,
    val kind: ArchiveKind,
    val title: String,
    val body: String = "",
    val sourceMessageId: Id? = null,
)

/**
 * 新修订：[baseRevision] 不是当前修订号 → 409 conflict_version。[id] 是这次修订的 id（客户端生成），重试不会多出修订。
 */
@Serializable
data class ReviseArchiveItemRequest(
    val id: Id,
    val baseRevision: Int,
    val title: String,
    val body: String = "",
    val sourceMessageId: Id? = null,
)

/** 一次修订（不可变）。 */
@Serializable
data class ArchiveRevision(
    val id: Id,
    val itemId: Id,
    val revision: Int,
    val authorId: Id,
    val title: String,
    val body: String,
    val sourceMessageId: Id?,
    val createdAt: Timestamp,
)
