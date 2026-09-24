package app.qichi.shared.api

import kotlinx.serialization.Serializable

/**
 * 同步实体 document：共同写作的一份文稿。只同步列表级信息；正文在不可变的版本里，按需另取。
 * [latestVersion] 为 0 表示还没有保存过任何版本。
 */
@Serializable
data class Document(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val createdBy: Id,
    val latestVersion: Int,
    /** 最新版本的作者；还没有版本时为空 */
    val latestAuthorId: Id?,
    /** 最新版本的中文字数 */
    val charCount: Int,
) : SyncEntity

@Serializable
data class CreateDocumentRequest(val id: Id, val title: String)

@Serializable
data class UpdateDocumentRequest(val title: String)

/** 版本列表里的一项（不含正文）。 */
@Serializable
data class DocumentVersionInfo(
    val id: Id,
    val documentId: Id,
    val version: Int,
    val baseVersion: Int,
    val authorId: Id,
    val charCount: Int,
    /** 旧版另存为新版时，来自哪个版本 */
    val restoredFromVersion: Int?,
    val createdAt: Timestamp,
)

/** 某个版本的完整内容。版本一旦保存就不再改变。 */
@Serializable
data class DocumentVersion(
    val id: Id,
    val documentId: Id,
    val version: Int,
    val baseVersion: Int,
    val authorId: Id,
    val charCount: Int,
    val restoredFromVersion: Int?,
    val createdAt: Timestamp,
    /** Markdown */
    val body: String,
)

@Serializable
data class DocumentVersionPage(
    /** 版本号从大到小 */
    val items: List<DocumentVersionInfo>,
    val nextCursor: String?,
)

/**
 * 保存新版本。[baseVersion] 是编辑时依据的版本（新文稿为 0）；不是最新 → 409 conflict_version。
 * 同一个 [id] 重试返回已经保存的那个版本。
 */
@Serializable
data class SaveDocumentVersionRequest(
    val id: Id,
    val baseVersion: Int,
    val body: String,
    val restoredFromVersion: Int? = null,
)

/**
 * 同步实体 doc_comment（P9-03）：文稿段落旁的留言。[parentId] 为空的是一条讨论的开头，钉在 [quote]（那一段或选中的原文）上；
 * 不为空的是它下面的回复（回复不钉位置、不能单独删）。文稿改了以后 App 按 [quote] 在最新正文里找回位置。
 * 开头可以标为解决（[resolvedAt]）；删除开头进回收站（只有作者能删）。
 */
@Serializable
data class DocComment(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val documentId: Id,
    val parentId: Id?,
    val authorId: Id,
    val body: String,
    /** 钉住的原文（开头才有） */
    val quote: String?,
    /** 写留言时看的是哪个版本（开头才有；还没保存过的文稿为 0） */
    val version: Int?,
    val resolvedAt: Timestamp?,
    val resolvedBy: Id?,
) : SyncEntity

/** 留言或回复：回复带 [parentId]，不带 [quote]。同一个 [id] 重试返回已有的那条。 */
@Serializable
data class CreateDocCommentRequest(
    val id: Id,
    val body: String,
    val parentId: Id? = null,
    val quote: String? = null,
    val version: Int? = null,
)

@Serializable
data class UpdateDocCommentRequest(val body: String)
