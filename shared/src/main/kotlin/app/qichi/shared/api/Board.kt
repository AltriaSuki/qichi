package app.qichi.shared.api

import app.qichi.shared.model.BoardReactionKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** 同步实体 board_topic：留言板的一个主题。[pinnedAt] 不为空表示置顶。 */
@Serializable
data class BoardTopic(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val authorId: Id,
    val pinnedAt: Timestamp?,
) : SyncEntity

/**
 * 同步实体 board_post：主题下的一条留言。可以引用另一条（[quoteExcerpt] 是引用时的摘录，原文之后改了也不变）。
 * 只有作者能修订；每次修订 [revision] 加一，旧内容进修订历史。
 */
@Serializable
data class BoardPost(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val topicId: Id,
    val authorId: Id,
    val body: String,
    val quotePostId: Id?,
    val quoteAuthorId: Id?,
    val quoteExcerpt: String?,
    /** 从 1 开始 */
    val revision: Int,
    /** 最近一次修订的时间；没修订过为空 */
    val revisedAt: Timestamp?,
) : SyncEntity

/** 同步实体 board_reaction：喜欢 / 拥抱 / 支持。收回 = 软删除。 */
@Serializable
data class BoardReaction(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val postId: Id,
    val authorId: Id,
    val kind: BoardReactionKind,
) : SyncEntity

@Serializable
data class CreateBoardTopicRequest(val id: Id, val title: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateBoardTopicRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pinned: Patch<Boolean> = Patch.Absent,
)

@Serializable
data class CreateBoardPostRequest(val id: Id, val body: String, val quotePostId: Id? = null)

/** 修订：[baseRevision] 不是当前修订号 → 409 conflict_version。 */
@Serializable
data class ReviseBoardPostRequest(val baseRevision: Int, val body: String)

@Serializable
data class PutBoardReactionRequest(val id: Id)

/** 一条留言的某个旧版本（不可变）。 */
@Serializable
data class BoardPostRevision(val revision: Int, val body: String, val createdAt: Timestamp)

@Serializable
data class BoardSearchResult(
    /** 正文含关键词的留言，新的在前 */
    val posts: List<BoardPost>,
    /** 标题含关键词的主题 */
    val topics: List<BoardTopic>,
)
