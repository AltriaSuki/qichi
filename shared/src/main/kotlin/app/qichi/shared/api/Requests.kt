package app.qichi.shared.api

import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.model.PushProvider
import app.qichi.shared.model.TrashType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── 第 2–3 阶段的请求与响应（openapi.yaml：moods、todos、events、messages、trash、devices）──

@Serializable
data class CreateMoodRequest(
    val id: Id,
    val label: MoodLabel,
    val intensity: Int,
    val note: String? = null,
    val needsComfort: Boolean = false,
)

@Serializable
data class CreateMoodReplyRequest(
    val id: Id,
    val kind: MoodReplyKind,
)

@Serializable
data class CreateTodoRequest(
    val id: Id,
    val title: String,
    val note: String? = null,
    val assigneeId: Id? = null,
    val parentId: Id? = null,
    val dueDate: Day? = null,
    val dueAt: Timestamp? = null,
    val recurrence: String? = null,
    val planId: Id? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateTodoRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val assigneeId: Patch<Id?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dueDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val dueAt: Patch<Timestamp?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val recurrence: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val planId: Patch<Id?> = Patch.Absent,
)

@Serializable
data class CompleteTodoRequest(
    /** 重复待办必填：下一次实例的 id */
    val nextId: Id? = null,
)

@Serializable
data class CompleteTodoResponse(
    val todo: Todo,
    /** 生成的下一次实例；不是重复待办时为 null */
    val next: Todo?,
)

@Serializable
data class CreateEventRequest(
    val id: Id,
    val title: String,
    val allDay: Boolean,
    val note: String? = null,
    val location: String? = null,
    val startsAt: Timestamp? = null,
    val endsAt: Timestamp? = null,
    val startDate: Day? = null,
    val endDate: Day? = null,
    val participantIds: List<Id> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateEventRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val location: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val allDay: Patch<Boolean> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startsAt: Patch<Timestamp?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val endsAt: Patch<Timestamp?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val endDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val participantIds: Patch<List<Id>> = Patch.Absent,
)

@Serializable
data class SendMessageRequest(
    val id: Id,
    /** text / image / file */
    val kind: String,
    val body: String? = null,
    val fileId: Id? = null,
    val replyToId: Id? = null,
)

@Serializable
data class MessagePage(
    /** 按 createdSeq 降序 */
    val messages: List<Message>,
    val hasMore: Boolean,
)

@Serializable
data class MessageSearchPage(
    val messages: List<Message>,
    val nextCursor: String?,
)

@Serializable
data class UpdateReadMarkerRequest(val lastReadSeq: Long)

@Serializable
data class TrashItem(
    val type: TrashType,
    val id: Id,
    val deletedAt: Timestamp,
    val deletedBy: Id,
    /** 结构由 type 决定：message → Message，mood → Mood，todo → Todo，event → Event */
    val data: JsonElement,
)

@Serializable
data class TrashPage(
    val items: List<TrashItem>,
    val nextCursor: String?,
)

@Serializable
data class RegisterDeviceRequest(
    val id: Id,
    val provider: PushProvider,
    val token: String,
)

@Serializable
data class Device(
    val id: Id,
    val provider: PushProvider,
    val token: String,
    val createdAt: Timestamp,
)
