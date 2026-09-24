package app.qichi.shared.api

import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.ArchiveKind
import kotlinx.serialization.Serializable

/**
 * AI 提议要记下的东西（草稿），字段按 [AiAction.kind] 取用：
 * - event：[title]、[note]、[location]；[allDay] 时用 [startDate]～[endDate]，否则 [startsAt]～[endsAt]
 * - todo：[title]、[note]、[assigneeId]、[dueDate] 或 [dueAt]、[planId]
 * - archive_item：[archiveKind]、[title]、[note]（正文）
 * - idea：[title]（正文）
 * 服务端从模型的回答里解析出来，名字、计划、时间都已经换成 id 和 UTC 时间。
 */
@Serializable
data class AiActionDraft(
    val title: String,
    val note: String? = null,
    val allDay: Boolean = false,
    val startsAt: Timestamp? = null,
    val endsAt: Timestamp? = null,
    val startDate: Day? = null,
    val endDate: Day? = null,
    val location: String? = null,
    val assigneeId: Id? = null,
    val dueDate: Day? = null,
    val dueAt: Timestamp? = null,
    val planId: Id? = null,
    val archiveKind: ArchiveKind? = null,
)

/**
 * 同步实体 ai_action（P8-02）：问 AI 时 AI 提议的一个动作，显示成回答下面的卡片。
 * AI 不自己写入任何东西：人点「好」（accept）才由服务端建成真正的日程 / 待办 / 档案 / 灵感（id = [resultId]），
 * 点「不用」（dismiss）就收起。没有回收站。
 */
@Serializable
data class AiAction(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    /** 属于哪条 AI 回答（= 那次问 AI 的 jobId） */
    val messageId: Id,
    /** 在这条回答里的顺序，从 0 开始 */
    val position: Int,
    val kind: AiActionKind,
    val draft: AiActionDraft,
    val status: AiActionStatus,
    /** 建成的实体 id；接受之前为空 */
    val resultId: Id?,
    val decidedBy: Id?,
    val requestedBy: Id?,
) : SyncEntity

/** 接受 AI 的提议：[resultId] 是要建的实体的 id（客户端生成），重试不会多建。 */
@Serializable
data class AcceptAiActionRequest(val resultId: Id)
