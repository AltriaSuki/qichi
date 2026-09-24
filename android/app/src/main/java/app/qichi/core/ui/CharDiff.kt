package app.qichi.core.ui

/**
 * 逐字对比（P9-04：AI 润色、改错别字的改前改后）。最长公共子序列，文字太长时不逐字比，整段当作换掉。
 * 相邻的同类片段合并成一段。
 */
object CharDiff {
    enum class Kind { Same, Removed, Added }

    data class Piece(val kind: Kind, val text: String)

    private const val MAX = 1_200

    fun diff(before: String, after: String): List<Piece> {
        if (before == after) return listOf(Piece(Kind.Same, before))
        if (before.length > MAX || after.length > MAX) {
            return listOfNotNull(before.takeIf { it.isNotEmpty() }?.let { Piece(Kind.Removed, it) }, after.takeIf { it.isNotEmpty() }?.let { Piece(Kind.Added, it) })
        }
        val n = before.length
        val m = after.length
        // lcs[i][j] = before[i..] 与 after[j..] 的最长公共子序列长度
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (before[i] == after[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Piece>()
        fun push(kind: Kind, c: Char) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.lastIndex] = last.copy(text = last.text + c) else out += Piece(kind, c.toString())
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                before[i] == after[j] -> { push(Kind.Same, before[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { push(Kind.Removed, before[i]); i++ }
                else -> { push(Kind.Added, after[j]); j++ }
            }
        }
        while (i < n) push(Kind.Removed, before[i++])
        while (j < m) push(Kind.Added, after[j++])
        return out
    }
}
