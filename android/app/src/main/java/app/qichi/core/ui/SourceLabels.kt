package app.qichi.core.ui

import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.fromWireOrNull

/** AI 引用的来源（总结、问 AI 的 [n]）是哪一类，给人看。 */
fun sourceKind(type: String) = when (type) {
    "message" -> "聊天"
    "decision" -> "决定"
    "idea" -> "灵感"
    "plan" -> "计划"
    "archive_item" -> "档案"
    "mood" -> "心情"
    "event" -> "日程"
    "todo" -> "待办"
    else -> "记录"
}

/** 来源的摘录：心情在服务端存的是英文值（如「calm 7/10，…」），这里换成中文的感受词。 */
fun sourceLabel(src: SummarySource): String {
    if (src.type != "mood") return src.label
    val m = Regex("^(\\w+) (\\d+)/10(.*)$").find(src.label) ?: return src.label
    val label = fromWireOrNull<MoodLabel>(m.groupValues[1]) ?: return src.label
    return feelingWord(label, m.groupValues[2].toInt()) + " " + m.groupValues[2] + m.groupValues[3]
}

/** 来源的图标和功能色（总结的来源列表、AI 回答的依据）。 */
fun sourceLook(type: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, app.qichi.core.designsystem.FeatureTone> = when (type) {
    "message" -> app.qichi.core.designsystem.icon.QichiIcons.Chat to app.qichi.core.designsystem.FeatureTone.PersonA
    "decision" -> app.qichi.core.designsystem.Feature.Decisions.icon to app.qichi.core.designsystem.Feature.Decisions.tone
    "idea" -> app.qichi.core.designsystem.Feature.Ideas.icon to app.qichi.core.designsystem.Feature.Ideas.tone
    "plan" -> app.qichi.core.designsystem.Feature.Plan.icon to app.qichi.core.designsystem.Feature.Plan.tone
    "archive_item" -> app.qichi.core.designsystem.Feature.Archive.icon to app.qichi.core.designsystem.Feature.Archive.tone
    "mood" -> app.qichi.core.designsystem.Feature.Mood.icon to app.qichi.core.designsystem.Feature.Mood.tone
    "event" -> app.qichi.core.designsystem.Feature.Calendar.icon to app.qichi.core.designsystem.Feature.Calendar.tone
    "todo" -> app.qichi.core.designsystem.Feature.Todo.icon to app.qichi.core.designsystem.Feature.Todo.tone
    // AI 自己查到的（P11）
    "qna_round" -> app.qichi.core.designsystem.Feature.Qna.icon to app.qichi.core.designsystem.Feature.Qna.tone
    "document" -> app.qichi.core.designsystem.Feature.Writing.icon to app.qichi.core.designsystem.Feature.Writing.tone
    "board_topic" -> app.qichi.core.designsystem.Feature.Board.icon to app.qichi.core.designsystem.Feature.Board.tone
    "book" -> app.qichi.core.designsystem.Feature.Reading.icon to app.qichi.core.designsystem.Feature.Reading.tone
    "review_document" -> app.qichi.core.designsystem.Feature.Review.icon to app.qichi.core.designsystem.Feature.Review.tone
    "summary" -> app.qichi.core.designsystem.Feature.Summary.icon to app.qichi.core.designsystem.Feature.Summary.tone
    else -> app.qichi.core.designsystem.icon.QichiIcons.Summary to app.qichi.core.designsystem.FeatureTone.Muted
}
