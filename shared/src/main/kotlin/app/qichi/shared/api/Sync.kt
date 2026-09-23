package app.qichi.shared.api

import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── 同步（openapi.yaml：sync；docs/05-sync-offline.md）──

/**
 * 一条变化。[data] 是实体的完整当前状态（结构由 [type] 决定，用 [EntityCodec] 解码）；
 * op = delete（彻底删除）时为 null。
 */
@Serializable
data class Change(
    val seq: Long,
    val type: EntityType,
    val id: Id,
    val op: ChangeOp,
    val data: JsonElement?,
)

@Serializable
data class SyncResponse(
    /** 等于请求的 since */
    val fromSeq: Long,
    /** 下次请求用它作为 since（可能大于 changes 里最大的 seq） */
    val toSeq: Long,
    val hasMore: Boolean,
    val changes: List<Change>,
)

@Serializable
data class Bootstrap(
    val room: Room,
    val members: List<Member>,
    val lastSeq: Long,
    /** 自己的未读位置；从未推进过时为 null */
    val readMarker: ReadMarker?,
    val moods: List<Mood>,
    val moodReplies: List<MoodReply>,
    val todos: List<Todo>,
    val events: List<Event>,
    /** 最近 50 条消息，按 createdSeq 降序 */
    val messages: List<Message>,
    val hasMoreMessages: Boolean,
    val questions: List<Question> = emptyList(),
    val qnaRounds: List<QnaRound> = emptyList(),
    val answers: List<Answer> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val planStages: List<PlanStage> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val planLogs: List<PlanLog> = emptyList(),
    val ideas: List<Idea> = emptyList(),
)

/** 实体类型 ⇄ 数据类的对应关系，两端共用。 */
object EntityCodec {
    @Suppress("UNCHECKED_CAST")
    fun serializer(type: EntityType): KSerializer<Any> = when (type) {
        EntityType.Room -> Room.serializer()
        EntityType.Member -> Member.serializer()
        EntityType.Message -> Message.serializer()
        EntityType.ReadMarker -> ReadMarker.serializer()
        EntityType.Mood -> Mood.serializer()
        EntityType.MoodResponse -> MoodReply.serializer()
        EntityType.Todo -> Todo.serializer()
        EntityType.Event -> Event.serializer()
        EntityType.Question -> Question.serializer()
        EntityType.QnaRound -> QnaRound.serializer()
        EntityType.Answer -> Answer.serializer()
        EntityType.Plan -> Plan.serializer()
        EntityType.PlanStage -> PlanStage.serializer()
        EntityType.Milestone -> Milestone.serializer()
        EntityType.PlanLog -> PlanLog.serializer()
        EntityType.Idea -> Idea.serializer()
    } as KSerializer<Any>

    fun decode(type: EntityType, data: JsonElement): Any = QichiJson.decodeFromJsonElement(serializer(type), data)

    fun encode(type: EntityType, value: Any): JsonElement = QichiJson.encodeToJsonElement(serializer(type), value)
}

// ── 实时通道 /ws：服务端 → 客户端 ──

@Serializable
sealed interface WsEvent {
    @Serializable
    @SerialName("hello")
    data class Hello(val userId: Id, val rooms: List<RoomSeq>) : WsEvent

    @Serializable
    @SerialName("changed")
    data class Changed(val roomId: Id, val seq: Long) : WsEvent

    /** 第 4 阶段启用 */
    @Serializable
    @SerialName("ai.done")
    data class AiDone(val roomId: Id, val jobId: Id, val status: String) : WsEvent
}

@Serializable
data class RoomSeq(val roomId: Id, val lastSeq: Long)
