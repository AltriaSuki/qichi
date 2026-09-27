package app.qichi.shared.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * AI 能看到哪些房间资料（存在 users.ai_prefs，经 PATCH /me 修改）。类别默认都能看。
 * 两个人都允许的类别 AI 才看得到（P11）：任何一人关掉，谁发起的 AI 请求都看不到。
 * [chat] 管的是「更早的聊天」：问 AI 时总会带上最近的几十条聊天（它就是在聊天里被问的）。
 * [reading] 默认只含公开的划线、摘录和 AI 解释；[readingPrivate] 管自己没公开的那些。
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
    /**
     * 我没公开的阅读记录（P14-02）：打开后，我没公开的划线、摘录、感想和 AI 解释也给 AI 用——谁问都一样，回答两个人都看得到。
     * 默认关。这一项各管各的，不取交集（见 [and]）；「阅读」这一类有人关掉时，照旧谁的都不给。
     */
    val readingPrivate: Boolean = false,
) {
    fun toJson(): JsonObject = QichiJson.encodeToJsonElement(this).jsonObject

    /**
     * 两个人的设置取交集（都允许才看）。[readingPrivate] 不在这里合：它说的是「我自己的」，服务端按人分别看，
     * 合出来的这一项总是 false。
     */
    infix fun and(other: AiPrefs) = AiPrefs(
        archive && other.archive, decisions && other.decisions, plans && other.plans, events && other.events,
        todos && other.todos, ideas && other.ideas, chat && other.chat, moods && other.moods,
        writing && other.writing, board && other.board, qna && other.qna, reading && other.reading,
        review && other.review, summaries && other.summaries,
        readingPrivate = false,
    )

    companion object {
        fun from(json: JsonObject?): AiPrefs =
            json?.let { runCatching { QichiJson.decodeFromJsonElement<AiPrefs>(it) }.getOrNull() } ?: AiPrefs()
    }
}
