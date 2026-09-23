package app.qichi.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 与 api/openapi.yaml 中同名的枚举一一对应（契约测试 EnumContractTest 会检查）。
// JSON 与数据库里存英文小写字符串（@SerialName），中文显示名只在客户端。
// 数据库存取用 wireName / fromWire（见 Wire.kt）。

/** 同步实体类型；也是 change_log.entity_type 的取值。后续阶段追加。 */
@Serializable
enum class EntityType {
    @SerialName("room") Room,
    @SerialName("member") Member,
    @SerialName("message") Message,
    @SerialName("read_marker") ReadMarker,
    @SerialName("mood") Mood,
    @SerialName("mood_response") MoodResponse,
    @SerialName("todo") Todo,
    @SerialName("event") Event,
    @SerialName("question") Question,
    @SerialName("qna_round") QnaRound,
    @SerialName("answer") Answer,
    @SerialName("plan") Plan,
    @SerialName("plan_stage") PlanStage,
    @SerialName("milestone") Milestone,
    @SerialName("plan_log") PlanLog,
    @SerialName("idea") Idea,
    @SerialName("document") Document,
    @SerialName("board_topic") BoardTopic,
    @SerialName("board_post") BoardPost,
    @SerialName("board_reaction") BoardReaction,
    @SerialName("archive_item") ArchiveItem,
    @SerialName("decision") Decision,
}

/** 变化类型：软删除与恢复都是 upsert，只有彻底删除是 delete。 */
@Serializable
enum class ChangeOp {
    @SerialName("upsert") Upsert,
    @SerialName("delete") Delete,
}

@Serializable
enum class MemberRole {
    @SerialName("owner") Owner,
    @SerialName("member") Member,
}

/** 心情标签：平静、开心、期待、疲惫、焦虑、低落、生气、委屈。 */
@Serializable
enum class MoodLabel {
    @SerialName("calm") Calm,
    @SerialName("happy") Happy,
    @SerialName("hopeful") Hopeful,
    @SerialName("tired") Tired,
    @SerialName("anxious") Anxious,
    @SerialName("down") Down,
    @SerialName("angry") Angry,
    @SerialName("hurt") Hurt,
}

/** 档案条目的种类：偏好、共识、决定、边界、担忧、里程碑。 */
@Serializable
enum class ArchiveKind {
    @SerialName("preference") Preference,
    @SerialName("consensus") Consensus,
    @SerialName("decision") Decision,
    @SerialName("boundary") Boundary,
    @SerialName("concern") Concern,
    @SerialName("milestone") Milestone,
}

/** 留言的回应：喜欢、拥抱、支持。 */
@Serializable
enum class BoardReactionKind {
    @SerialName("like") Like,
    @SerialName("hug") Hug,
    @SerialName("support") Support,
}

/** 对心情的回应：我在这里、给你一个拥抱、等你准备好。 */
@Serializable
enum class MoodReplyKind {
    @SerialName("here") Here,
    @SerialName("hug") Hug,
    @SerialName("ready") Ready,
}

@Serializable
enum class MessageKind {
    @SerialName("text") Text,
    @SerialName("image") Image,
    @SerialName("file") File,
    @SerialName("ai") Ai,
    @SerialName("system") System,
}

@Serializable
enum class FileKind {
    @SerialName("image") Image,
    @SerialName("file") File,
    @SerialName("avatar") Avatar,
    @SerialName("hero") Hero,
    @SerialName("epub") Epub,
    @SerialName("review") Review,
}

/** 回收站里可恢复的类型。后续阶段追加。 */
@Serializable
enum class TrashType {
    @SerialName("message") Message,
    @SerialName("mood") Mood,
    @SerialName("todo") Todo,
    @SerialName("event") Event,
    @SerialName("question") Question,
    @SerialName("plan") Plan,
    @SerialName("idea") Idea,
    @SerialName("document") Document,
    @SerialName("board_topic") BoardTopic,
    @SerialName("board_post") BoardPost,
    @SerialName("archive_item") ArchiveItem,
    @SerialName("decision") Decision,
}

@Serializable
enum class PlanStatus {
    @SerialName("active") Active,
    @SerialName("done") Done,
    @SerialName("archived") Archived,
}

@Serializable
enum class PushProvider {
    @SerialName("fcm") Fcm,
    @SerialName("unifiedpush") UnifiedPush,
}

/** problem+json 里的 code；客户端按它判断错误，不看 title。 */
@Serializable
enum class ProblemCode {
    @SerialName("invalid_request") InvalidRequest,
    @SerialName("unauthorized") Unauthorized,
    @SerialName("forbidden") Forbidden,
    @SerialName("not_found") NotFound,
    @SerialName("conflict_version") ConflictVersion,
    @SerialName("conflict_id") ConflictId,
    @SerialName("room_full") RoomFull,
    @SerialName("username_taken") UsernameTaken,
    @SerialName("invite_invalid") InviteInvalid,
    @SerialName("registration_closed") RegistrationClosed,
    @SerialName("payload_too_large") PayloadTooLarge,
    @SerialName("unsupported_media_type") UnsupportedMediaType,
    @SerialName("rate_limited") RateLimited,
    @SerialName("ai_unavailable") AiUnavailable,
    @SerialName("ai_quota_exceeded") AiQuotaExceeded,
    @SerialName("internal_error") InternalError,
}

/** AI 调用的种类（ai_jobs.kind）。 */
@Serializable
enum class AiJobKind {
    @SerialName("chat_answer") ChatAnswer,
    @SerialName("question_suggest") QuestionSuggest,
    @SerialName("read_explain") ReadExplain,
    @SerialName("review_findings") ReviewFindings,
    @SerialName("summary") Summary,
    @SerialName("yearly_review") YearlyReview,
}

/** AI 任务的状态。 */
@Serializable
enum class AiJobStatus {
    @SerialName("queued") Queued,
    @SerialName("running") Running,
    @SerialName("done") Done,
    @SerialName("failed") Failed,
}

@Serializable
enum class QuestionSource {
    @SerialName("ai") Ai,
    @SerialName("user") User,
    @SerialName("preset") Preset,
}
