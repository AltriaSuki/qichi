package app.qichi.shared.rules

/** 留言板的共用规则（两端一致）。 */
object BoardRules {
    private val markers = Regex("^\\s*(#{1,6}|[-*+>]|\\d{1,3}[.)])\\s+", RegexOption.MULTILINE)
    private val spaces = Regex("\\s+")

    /** 引用时的摘录：去掉行首的 Markdown 标记，空白合成一个空格，最多 [Limits.BOARD_QUOTE_EXCERPT] 字，超出加省略号。 */
    fun quoteExcerpt(body: String): String {
        val flat = body.replace(markers, "").replace(spaces, " ").trim()
        val max = Limits.BOARD_QUOTE_EXCERPT
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
