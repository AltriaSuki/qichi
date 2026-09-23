package app.qichi.shared.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 通知偏好（存在 users.notification_prefs，经 PATCH /me 修改）。推送（P3-10）按它决定发不发：
 * 各类开关，以及免打扰时段（房间时区，[quietStart] 到 [quietEnd]，可以跨午夜）。
 * 推送里只有「谁做了什么」，不含正文。
 */
@Serializable
data class NotificationPrefs(
    val messages: Boolean = true,
    val moods: Boolean = true,
    val qna: Boolean = true,
    val todos: Boolean = true,
    val events: Boolean = true,
    val board: Boolean = true,
    val quietEnabled: Boolean = false,
    /** 形如 22:00 */
    val quietStart: String = "22:00",
    val quietEnd: String = "08:00",
) {
    fun toJson(): JsonObject = QichiJson.encodeToJsonElement(this).jsonObject

    companion object {
        /** 旧数据缺的字段用默认值；解析不了就全用默认值。 */
        fun from(json: JsonObject): NotificationPrefs = runCatching { QichiJson.decodeFromJsonElement<NotificationPrefs>(json) }.getOrDefault(NotificationPrefs())
    }
}
