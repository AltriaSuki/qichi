package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.EntityRow
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.TypeCount
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.network.post
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateMeRequest
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 「我的」里账号相关的：我写下的内容（本机归总）、登录设备、改密码、通知偏好。
 * 登录设备、改密码、通知偏好都要联网（不进发件箱）。
 */
class AccountRepository(
    private val db: QichiDatabase,
    private val api: ApiClient,
    private val session: SessionManager,
    private val rooms: RoomRepository,
) {
    fun observeMyCounts(roomId: UUID, me: UUID, types: List<String>): Flow<List<TypeCount>> =
        db.entities().observeCountsByOwner(roomId.toString(), me.toString(), types)

    fun observeMyRecent(roomId: UUID, me: UUID, types: List<String>, limit: Int = 30): Flow<List<EntityRow>> =
        db.entities().observeRecentByOwner(roomId.toString(), me.toString(), types, limit)

    suspend fun sessions(): List<LoginSession> = api.get("me/sessions")

    suspend fun revoke(sessionId: UUID) {
        api.execute(HttpMethod.Delete, "me/sessions/$sessionId")
    }

    /** 改密码：其它设备都会退出登录；这台换上新的令牌。 */
    suspend fun changePassword(current: String, new: String) {
        val tokens = api.post<AuthTokens>("me/password", ChangePasswordRequest(current, new))
        session.replaceTokens(tokens)
    }

    suspend fun updateNotifications(prefs: NotificationPrefs) {
        rooms.updateMe(UpdateMeRequest(notificationPrefs = Patch.of(prefs.toJson())))
    }
}
