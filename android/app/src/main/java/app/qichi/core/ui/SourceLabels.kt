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
