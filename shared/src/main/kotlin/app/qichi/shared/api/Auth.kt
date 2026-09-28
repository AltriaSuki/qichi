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

/** 忘了密码（P16-04）：用房间里另一个人生成的一次性重置码设新密码，成功后直接登录。 */
@Serializable
data class ResetPasswordRequest(
    val username: String,
    val code: String,
    val newPassword: String,
    val deviceName: String? = null,
)

/** 给房间里另一个人生成的重置码：只显示这一次，[expiresAt] 之后作废。 */
@Serializable
data class PasswordResetCode(
    val code: String,
    val expiresAt: Timestamp,
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
    /** AI 能看到哪些资料（[AiPrefs]）；旧服务端没有这个字段 */
    val aiPrefs: JsonObject = JsonObject(emptyMap()),
    /** 阅读里的常用提示词（P14-05），顺序即显示顺序；旧服务端没有这个字段 */
    val readingPrompts: List<ReadingPrompt> = emptyList(),
)

/** 阅读里的一条常用提示词（P14-05）：每人一套，存在账号上，对方看不到。 */
@Serializable
data class ReadingPrompt(
    val id: Id,
    /** 显示在按钮上的名字，1–20 字 */
    val title: String,
    /** 给 AI 的要求，1–300 字 */
    val instruction: String,
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val aiPrefs: Patch<JsonObject> = Patch.Absent,
    /** 整套替换常用提示词（P14-05） */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val readingPrompts: Patch<List<ReadingPrompt>> = Patch.Absent,
)
