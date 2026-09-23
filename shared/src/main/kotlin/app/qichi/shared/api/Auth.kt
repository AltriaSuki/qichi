package app.qichi.shared.api

import app.qichi.shared.model.MemberRole
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── 账号（openapi.yaml：auth / me）──

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String,
    val inviteCode: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** 一次登录（= 一台设备）。[current] 表示就是发出这次请求的设备。 */
@Serializable
data class LoginSession(
    val id: Id,
    val deviceName: String?,
    val createdAt: Timestamp,
    val lastUsedAt: Timestamp,
    val current: Boolean,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Timestamp,
    val refreshToken: String,
    val refreshTokenExpiresAt: Timestamp,
)

@Serializable
data class User(
    val id: Id,
    val username: String,
    val displayName: String,
    val avatarFileId: Id?,
    /** 通知偏好，具体字段在 P3-10 确定 */
    val notificationPrefs: JsonObject,
    val createdAt: Timestamp,
)

@Serializable
data class MyRoom(
    val roomId: Id,
    val name: String,
    val role: MemberRole,
)

@Serializable
data class Me(
    val user: User,
    val rooms: List<MyRoom>,
    /** 服务端配置了 AI（否则 App 里的 AI 按钮显示为不可用） */
    val aiEnabled: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateMeRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val displayName: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val avatarFileId: Patch<Id?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notificationPrefs: Patch<JsonObject> = Patch.Absent,
)
