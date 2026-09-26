package app.qichi.shared.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * AI 能看到哪些房间资料（存在 users.ai_prefs，经 PATCH /me 修改）。默认都能看。
 * 两个人都允许的类别 AI 才看得到（P11）：任何一人关掉，谁发起的 AI 请求都看不到。
 * [chat] 管的是「更早的聊天」：问 AI 时总会带上最近的几十条聊天（它就是在聊天里被问的）。
 * [reading] 只含公开的摘录；没公开的摘录永远不给 AI。
 */
@Serializable
data class AiPrefs(
    val archive: Boolean = true,
    val decisions: Boolean = true,
    val plans: Boolean = true,
    val events: Boolean = true,
    val todos: Boolean = true,
    val ideas: Boolean = true,
    val chat: Boolean = true,
    val moods: Boolean = true,
    val writing: Boolean = true,
    val board: Boolean = true,
    val qna: Boolean = true,
    val reading: Boolean = true,
    val review: Boolean = true,
    val summaries: Boolean = true,
) {
    fun toJson(): JsonObject = QichiJson.encodeToJsonElement(this).jsonObject

    /** 两个人的设置取交集（都允许才看）。 */
    infix fun and(other: AiPrefs) = AiPrefs(
        archive && other.archive, decisions && other.decisions, plans && other.plans, events && other.events,
        todos && other.todos, ideas && other.ideas, chat && other.chat, moods && other.moods,
        writing && other.writing, board && other.board, qna && other.qna, reading && other.reading,
        review && other.review, summaries && other.summaries,
    )

    companion object {
        fun from(json: JsonObject?): AiPrefs =
            json?.let { runCatching { QichiJson.decodeFromJsonElement<AiPrefs>(it) }.getOrNull() } ?: AiPrefs()
    }
}
