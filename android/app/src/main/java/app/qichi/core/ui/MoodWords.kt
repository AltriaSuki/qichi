package app.qichi.core.ui

import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind

/** 心情标签的中文名（情绪选择用）。 */
val MoodLabel.displayName: String
    get() = when (this) {
        MoodLabel.Calm -> "平静"
        MoodLabel.Happy -> "开心"
        MoodLabel.Hopeful -> "期待"
        MoodLabel.Tired -> "疲惫"
        MoodLabel.Anxious -> "焦虑"
        MoodLabel.Down -> "低落"
        MoodLabel.Angry -> "生气"
        MoodLabel.Hurt -> "委屈"
    }

/**
 * 心情词：标签 × 强度说成一句口语，如「疲惫 6」→「有点累」（设计稿 Mood / Main 的写法）。
 * 强度分三档：1–3、4–6、7–10。
 */
fun feelingWord(label: MoodLabel, intensity: Int): String {
    val band = when {
        intensity <= 3 -> 0
        intensity <= 6 -> 1
        else -> 2
    }
    val words = when (label) {
        MoodLabel.Calm -> listOf("还算平静", "平静", "很安宁")
        MoodLabel.Happy -> listOf("有点开心", "开心", "很开心")
        MoodLabel.Hopeful -> listOf("有点期待", "期待", "很期待")
        MoodLabel.Tired -> listOf("有些倦", "有点累", "很累")
        MoodLabel.Anxious -> listOf("有些不安", "有点焦虑", "很焦虑")
        MoodLabel.Down -> listOf("情绪有点低", "有点低落", "很低落")
        MoodLabel.Angry -> listOf("有点烦", "有点生气", "很生气")
        MoodLabel.Hurt -> listOf("有点不是滋味", "有点委屈", "很委屈")
    }
    return words[band]
}

/** 对心情的三种低压力回应。 */
val MoodReplyKind.displayName: String
    get() = when (this) {
        MoodReplyKind.Here -> "我在这里"
        MoodReplyKind.Hug -> "给你一个拥抱"
        MoodReplyKind.Ready -> "等你准备好"
    }
