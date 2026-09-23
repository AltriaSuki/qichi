package app.qichi.shared.api

import kotlinx.serialization.Serializable

/** ICS 上传完成后的统计。跳过已导入 UID 和不支持的事件。 */
@Serializable
data class CalendarImportResult(val imported: Int, val skipped: Int)

/** 首次生成时 reset=false；明确要求换链接时 reset=true。 */
@Serializable
data class CalendarSubscriptionRequest(val reset: Boolean = false)

/** URL 本身包含只读凭证，只供两位成员查看与分享。 */
@Serializable
data class CalendarSubscription(val url: String)
