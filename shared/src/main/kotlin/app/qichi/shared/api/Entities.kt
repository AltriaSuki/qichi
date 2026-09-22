package app.qichi.shared.api

import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import kotlinx.serialization.Serializable

// ── 第 1–3 阶段的同步实体（openapi.yaml：Message、ReadMarker、Mood、MoodReply、Todo、Event）──
// 共有字段：id、roomId、seq、createdAt、updatedAt、deletedAt（不为空 = 在回收站）、deletedBy。

/** 可以出现在同步里的实体。 */
sealed interface SyncEntity {
    val id: Id
    val seq: Long
}

@Serializable
data class FileMeta(
    val id: Id,
    val roomId: Id,
    val kind: FileKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    val uploadedBy: Id,
    val createdAt: Timestamp,
)

@Serializable
data class Message(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    /** ai / system 消息为空 */
    val authorId: Id?,
    val kind: MessageKind,
    /** 撤回后为空字符串 */
    val body: String,
    /** 图片或文件消息的附件；撤回后为 null */
    val file: FileMeta?,
    val replyToId: Id?,
    val replyAuthorId: Id?,
    val replyExcerpt: String?,
    val retractedAt: Timestamp?,
    val retractedBy: Id?,
    /** 创建时分配的 seq，决定消息在聊天里的位置，之后不变 */
    val createdSeq: Long,
    /** AI 回答（kind = ai）是回答哪个问题的；其它消息为空 */
    val aiPrompt: String? = null,
) : SyncEntity

/** 只同步给本人（不做已读回执）。 */
@Serializable
data class ReadMarker(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val userId: Id,
    /** 读到的最后一条消息的 createdSeq */
    val lastReadSeq: Long,
) : SyncEntity

@Serializable
data class Mood(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val authorId: Id,
    val label: MoodLabel,
    val intensity: Int,
    val note: String?,
    val needsComfort: Boolean,
) : SyncEntity

/** 同步实体 mood_response；收回 = 软删除。 */
@Serializable
data class MoodReply(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val moodId: Id,
    val authorId: Id,
    val kind: MoodReplyKind,
) : SyncEntity

@Serializable
data class Todo(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val note: String?,
    val createdBy: Id,
    /** 为空 = 两个人 */
    val assigneeId: Id?,
    /** 不为空 = 子任务 */
    val parentId: Id?,
    /** 只有日期的截止（按房间时区）；与 dueAt 至多一个有值 */
    val dueDate: Day?,
    val dueAt: Timestamp?,
    /** RRULE，如 FREQ=WEEKLY;INTERVAL=1;BYDAY=SU */
    val recurrence: String?,
    /** 由哪一条重复待办的完成生成 */
    val recurrencePrevId: Id?,
    val doneAt: Timestamp?,
    val doneBy: Id?,
    /** 属于哪个计划 */
    val planId: Id? = null,
) : SyncEntity

/**
 * 定时日程：allDay = false，用 startsAt / endsAt；
 * 全天日程：allDay = true，用 startDate / endDate（含首尾，按房间时区）。另一组为 null。
 */
@Serializable
data class Event(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val note: String?,
    val location: String?,
    val allDay: Boolean,
    val startsAt: Timestamp?,
    val endsAt: Timestamp?,
    val startDate: Day?,
    val endDate: Day?,
    /** 为空 = 两个人 */
    val participantIds: List<Id>,
    val createdBy: Id,
    val icsUid: String?,
) : SyncEntity
