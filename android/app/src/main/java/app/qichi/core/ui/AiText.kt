package app.qichi.core.ui

// AI 的回答存到别处（灵感、文稿、档案，P14-04）时用的文字。

/** 回答里的来源编号 [n]：在聊天里点开能跳到原来那条，存到别处就点不开了。 */
private val CITATION = Regex("""\s?\[\d{1,4}]""")

/** 存到别处的正文：去掉来源编号。[markdown] 为 false 时连 Markdown 符号也去掉（灵感、档案只显示纯文字；文稿保留）。 */
fun plainAiAnswer(body: String, markdown: Boolean = true): String =
    body.replace(CITATION, "").let { if (markdown) it else Markdown.plain(it) }.trim()

/** 存成文稿、档案时的标题：问的那句话的第一行，太长截到 [max] 个字（最后一个字换成省略号）；没有问题时用 [fallback]。 */
fun aiAnswerTitle(prompt: String?, max: Int, fallback: String = "AI 的回答"): String {
    val line = prompt?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return when {
        line.isEmpty() -> fallback
        line.length <= max -> line
        else -> line.take(max - 1) + "…"
    }
}
