package app.qichi.feature.reading

import app.qichi.shared.api.ReadingPrompt

/** 当场写的要求存成常用时的名字：取第一行开头几个字（名字最多 20 字）。 */
internal fun promptTitle(instruction: String): String {
    val firstLine = instruction.trim().lineSequence().first().trim()
    return if (firstLine.length <= PROMPT_TITLE_FROM_TEXT) firstLine else firstLine.take(PROMPT_TITLE_FROM_TEXT) + "…"
}

private const val PROMPT_TITLE_FROM_TEXT = 12

/** 把第 [from] 条挪到第 [to] 条的位置（调常用提示词的顺序）；越界时原样返回。 */
internal fun List<ReadingPrompt>.moved(from: Int, to: Int): List<ReadingPrompt> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
