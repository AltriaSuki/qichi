package app.qichi.shared.api

import app.qichi.shared.model.HighlightKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * 同步实体 book：书架上的一本书。EPUB 文件先传到 /files（kind = epub），这里只记元数据与共读计划。
 * 书的正文不进同步，按需下载、缓存在手机上。
 */
@Serializable
data class Book(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val author: String?,
    val fileId: Id,
    val sizeBytes: Long,
    val addedBy: Id,
    /** 共读计划：打算什么时候一起读完 */
    val planTargetDate: Day?,
    val planNote: String?,
) : SyncEntity

/** 同步实体 reading_progress：一个人在一本书里读到哪里（各自独立）。[locator] 是 Readium 定位的 JSON。 */
@Serializable
data class ReadingProgress(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val bookId: Id,
    val userId: Id,
    val locator: String,
    /** 0–1 */
    val progress: Double,
) : SyncEntity

/**
 * 同步实体 highlight：书签、标注、摘录。[note] 是写在旁边的感想；[shared] 为 true 时对方也看得到，
 * 否则只同步给自己（对方的手机上没有这条）。
 */
@Serializable
data class Highlight(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val bookId: Id,
    val userId: Id,
    val kind: HighlightKind,
    val locator: String,
    /** 选中的原文（书签为空） */
    val text: String,
    val note: String?,
    val shared: Boolean,
) : SyncEntity

@Serializable
data class CreateBookRequest(val id: Id, val fileId: Id, val title: String, val author: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateBookRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val author: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val planTargetDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val planNote: Patch<String?> = Patch.Absent,
)

/** 保存我的进度；[id] 是本机这条进度的 id（服务端已有我的进度时沿用已有的 id）。 */
@Serializable
data class PutReadingProgressRequest(val id: Id, val locator: String, val progress: Double)

@Serializable
data class CreateHighlightRequest(
    val id: Id,
    val kind: HighlightKind,
    val locator: String,
    val text: String = "",
    val note: String? = null,
    val shared: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateHighlightRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shared: Patch<Boolean> = Patch.Absent,
)
