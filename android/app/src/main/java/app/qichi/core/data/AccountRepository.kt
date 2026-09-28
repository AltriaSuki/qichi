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
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.api.Patch
import app.qichi.shared.api.ReadingPrompt
import app.qichi.shared.api.UpdateMeRequest
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 「我的」里账号相关的：我写下的内容（本机归总）、登录设备、改密码、通知偏好、AI 能看什么、阅读的常用提示词。
 * 除了本机归总，都要联网（不进发件箱）。
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

    /**
     * 免打扰按自己手机的时区算（P16-10）：服务器上记的和手机现在的不一样时改过去。
     * 每次回到前台看一次（出差、换了时区后打开 App 就会更新）；没联网就下次再说。
     */
    suspend fun reportPhoneZone(zone: String) {
        val me = rooms.me.first() ?: return
        val prefs = NotificationPrefs.from(me.user.notificationPrefs)
        if (prefs.timezone == zone) return
        updateNotifications(prefs.copy(timezone = zone))
    }

    suspend fun updateAiPrefs(prefs: AiPrefs) {
        rooms.updateMe(UpdateMeRequest(aiPrefs = Patch.of(prefs.toJson())))
    }

    /** 阅读的常用提示词整套换成 [prompts]（P14-05；顺序即显示顺序）。 */
    suspend fun updateReadingPrompts(prompts: List<ReadingPrompt>) {
        rooms.updateMe(UpdateMeRequest(readingPrompts = Patch.of(prompts)))
    }
}
