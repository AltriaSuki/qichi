package app.qichi.server.ai

/**
 * 给 AI 看的心情词：心情在库里存英文值（calm、tired……），直接给模型它会照抄进回答（「心情是 calm」）。
 * 只用在提示词里；App 显示的中文名在客户端。
 */
object MoodWords {
    private val words = mapOf(
        "calm" to "平静", "happy" to "开心", "hopeful" to "期待", "tired" to "疲惫",
        "anxious" to "焦虑", "down" to "低落", "angry" to "生气", "hurt" to "委屈",
    )

    fun of(label: String): String = words[label] ?: label

    /** 「疲惫 6/10，加班」 */
    fun line(label: String, intensity: Number, note: String?): String = "${of(label)} $intensity/10" + (note?.let { "，$it" } ?: "")
}
