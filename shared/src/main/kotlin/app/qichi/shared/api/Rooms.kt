package app.qichi.shared.api

import app.qichi.shared.model.MemberRole
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// ── 房间（openapi.yaml：rooms）──

/** 同步实体 room。房间没有删除。 */
@Serializable
data class Room(
    val id: Id,
    val name: String,
    val avatarFileId: Id?,
    /** 今天页主视觉照片；为空时显示雾海插画 */
    val heroFileId: Id?,
    val anniversary: Day?,
    /** IANA 时区名；「今天」「本周」按它计算 */
    val timezone: String,
    /** 创建者用 personA 的颜色，另一位用 personB */
    val createdBy: Id,
    val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/** 同步实体 member。 */
@Serializable
data class Member(
    val id: Id,
    val roomId: Id,
    val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val userId: Id,
    val role: MemberRole,
    val username: String,
    val displayName: String,
    val avatarFileId: Id?,
    val joinedAt: Timestamp,
)

@Serializable
data class RoomDetail(
    val room: Room,
    val members: List<Member>,
    val lastSeq: Long,
)

@Serializable
data class CreateRoomRequest(
    val id: Id,
    val name: String,
    val timezone: String? = null,
    val anniversary: Day? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateRoomRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val avatarFileId: Patch<Id?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val heroFileId: Patch<Id?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val anniversary: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val timezone: Patch<String> = Patch.Absent,
)

@Serializable
data class Invite(
    val code: String,
    val roomId: Id,
    val expiresAt: Timestamp,
    val createdAt: Timestamp,
)

@Serializable
data class AcceptInviteRequest(val code: String)
