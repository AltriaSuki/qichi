package app.qichi.shared.api

import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiJobStatus
import kotlinx.serialization.Serializable

/** 问 AI（聊天里）：[jobId] 由客户端生成；回答会成为一条 id 等于 jobId 的 AI 消息。 */
@Serializable
data class AiChatRequest(
    val jobId: Id,
    val prompt: String,
)

/** AI 请求被接受（202）：结果稍后写进对应实体，并通过 WebSocket 发 ai.done。 */
@Serializable
data class AiJobAccepted(
    val jobId: Id,
    val status: AiJobStatus,
)

/** 一次 AI 调用的记录。 */
@Serializable
data class AiJob(
    val id: Id,
    val roomId: Id,
    val kind: AiJobKind,
    val status: AiJobStatus,
    val model: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    /** 结果写到了哪里，如 "message:<uuid>" */
    val resultRef: String?,
    /** 失败原因（给人看的一句话） */
    val error: String?,
    val createdAt: Timestamp,
    val finishedAt: Timestamp?,
)

/** 「我发起的 AI 使用」：某个月里我发起的调用，以及整个服务本月的额度情况。 */
@Serializable
data class AiUsage(
    /** 形如 2026-09 */
    val month: String,
    val jobs: List<AiJob>,
    val myInputTokens: Long,
    val myOutputTokens: Long,
    /** 本月全部用量（两个人加起来）与上限 */
    val monthUsedTokens: Long,
    val monthLimitTokens: Long,
)
