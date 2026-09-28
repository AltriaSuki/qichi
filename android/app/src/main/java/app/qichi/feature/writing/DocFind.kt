package app.qichi.feature.writing

/** 文稿里查找、替换（P19-04）：不分大小写，找到的不重叠。 */
internal object DocFind {
    /** [text] 里所有 [query] 的位置；[query] 为空时没有。 */
    fun matches(text: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val found = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - query.length) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            found += at until at + query.length
            from = at + query.length
        }
        return found
    }

    /** 光标 [cursor] 处或之后的第一处；都在前面就回到第一处；没有为 -1。 */
    fun nearest(matches: List<IntRange>, cursor: Int): Int {
        if (matches.isEmpty()) return -1
        return matches.indexOfFirst { it.first >= cursor }.takeIf { it >= 0 } ?: 0
    }

    /** 把 [text] 里的 [range] 换成 [replacement]。 */
    fun replaceAt(text: String, range: IntRange, replacement: String): String =
        text.substring(0, range.first) + replacement + text.substring(range.last + 1)

    /** 全部替换：返回新正文和换了几处。 */
    fun replaceAll(text: String, query: String, replacement: String): Pair<String, Int> {
        val found = matches(text, query)
        if (found.isEmpty()) return text to 0
        val out = StringBuilder()
        var last = 0
        found.forEach { r ->
            out.append(text, last, r.first).append(replacement)
            last = r.last + 1
        }
        out.append(text, last, text.length)
        return out.toString() to found.size
    }
}
