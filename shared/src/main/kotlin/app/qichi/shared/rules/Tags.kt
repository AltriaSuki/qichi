package app.qichi.shared.rules

/**
 * 正文里的 #标签（灵感、档案用）：`#词` 或 `#词/子词`，最多三层，两端共用同一份规则。
 *
 * - 标签里的字：任何文字的字母和数字（汉字、英文、日文……）、下划线、连字符；遇到空白、标点、`#` 就结束。
 * - `#` 后面紧跟空白或标点的不算（「# 周末」「#！」）。
 * - `#` 前面紧跟英文字母或数字的不算（`C#`、`abc#def`），免得把普通文字误认成标签；
 *   前面是汉字没关系（「学做红烧肉#吃」），中文里常常不空格。
 * - 结尾的 `/`（「#旅行/」）去掉；空的一层（「#旅行//北方」）跳过；超过三层的只取前三层。
 * - 英文不区分大小写时也按原样保存，比较时按原样比较（「#Trip」和「#trip」是两个标签）。
 *
 * 标签不另存：本机按正文算；改名 / 合并就是批量改正文（[rename]）。
 */
object Tags {
    const val MAX_DEPTH: Int = 3

    /** 正文里出现的标签（去掉 `#`，按出现顺序，不重复），如 `旅行/北方`。 */
    fun parse(text: String): List<String> = matches(text).map { it.tag }.distinct()

    /** 某个标签和它所有的上层：`旅行/北方/雪山` → `旅行`、`旅行/北方`、`旅行/北方/雪山`。 */
    fun withAncestors(tag: String): List<String> {
        val parts = tag.split('/')
        return parts.indices.map { i -> parts.subList(0, i + 1).joinToString("/") }
    }

    /** 这段正文算不算在 [tag] 下面（它本身，或它的子标签）。 */
    fun has(text: String, tag: String): Boolean = parse(text).any { it == tag || it.startsWith("$tag/") }

    /**
     * 把正文里的 [from]（以及它下面的子标签）改成 [to]：`#旅行/北方` 在 `旅行` → `出门` 时变成 `#出门/北方`。
     * 合并就是改成一个已有的名字。没有变化时原样返回。
     */
    fun rename(text: String, from: String, to: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in matches(text)) {
            val newTag = when {
                m.tag == from -> to
                m.tag.startsWith("$from/") -> to + m.tag.removePrefix(from)
                else -> continue
            }
            sb.append(text, last, m.start).append('#').append(newTag)
            last = m.end
        }
        if (last == 0) return text
        return sb.append(text, last, text.length).toString()
    }

    /** 一个合法的标签名（改名时检查新名字）：一到三层，每层是标签字，不含 `#`。 */
    fun isValid(tag: String): Boolean {
        val parts = tag.split('/')
        return parts.size in 1..MAX_DEPTH && parts.all { p -> p.isNotEmpty() && p.all(::isTagChar) }
    }

    private class Match(val start: Int, val end: Int, val tag: String)

    private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    private fun matches(text: String): List<Match> {
        val out = mutableListOf<Match>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '#' || (i > 0 && text[i - 1].let { it < '\u0080' && it.isLetterOrDigit() })) {
                i++
                continue
            }
            // 读到空白、标点、#（除了 / 分层）为止
            var j = i + 1
            while (j < text.length && (isTagChar(text[j]) || text[j] == '/')) j++
            val raw = text.substring(i + 1, j)
            val parts = raw.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || raw.startsWith('/')) {
                i = j.coerceAtLeast(i + 1)
                continue
            }
            val kept = parts.take(MAX_DEPTH)
            // 替换时只换掉真正属于标签的那一段（多出的层和结尾的 / 留在原文里）
            var end = i + 1
            var seen = 0
            var k = i + 1
            while (k < j && seen < kept.size) {
                val next = text.indexOf('/', k).let { if (it == -1 || it > j) j else it }
                if (next > k) { seen++; end = next }
                k = next + 1
            }
            out += Match(i, end, kept.joinToString("/"))
            i = j
        }
        return out
    }
}
