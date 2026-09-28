package app.qichi.core.ui

/** 文字里的一个网址：在原文里的位置 [start, end) 和点了要打开的地址。 */
data class LinkSpan(val start: Int, val end: Int, val url: String)

/**
 * 网址：http:// 或 https:// 开头，或者 www. 开头；遇到空白、中文、全角标点为止。
 * 中文里常直接连着写（「看这个https://a.cn/x很好」），所以中文字不算网址的一部分。
 */
private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'　-〿一-鿿＀-￯‘-‟]+""")

/** 句末常跟着的标点不算进网址（括号成对时留着，如维基百科的「_(消歧义)」）。 */
private const val TRAILING = ".,;:!?'\")]}>"

/** 找出 [text] 里的网址，按出现顺序。 */
fun findLinks(text: String): List<LinkSpan> = urlPattern.findAll(text).mapNotNull { m ->
    val start = m.range.first
    var end = m.range.last + 1
    while (end > start && text[end - 1] in TRAILING) {
        val c = text[end - 1]
        val open = when (c) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
        // 右括号：前面有没配对的左括号就是网址自己的，留下
        if (open != null) {
            val body = text.substring(start, end)
            if (body.count { it == open } >= body.count { it == c }) break
        }
        end--
    }
    val raw = text.substring(start, end)
    val scheme = raw.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    // 只有「https://」「www.」本身，后面没东西
    if (raw.length - scheme < 4 || !raw.substring(scheme).contains('.')) return@mapNotNull null
    LinkSpan(start, end, if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw)
}.toList()
