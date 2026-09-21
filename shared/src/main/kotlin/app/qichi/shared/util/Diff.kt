package app.qichi.shared.util

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

/**
 * 逐行对比（共同写作的历史版本对比、重基线对比）。基于 java-diff-utils（Myers 算法）。
 *
 * 结果按阅读顺序列出两份文本的每一行：相同的行、被删掉的行、新加的行。
 * 修改过的一段表示为「先列出删掉的旧行，再列出新加的新行」。
 */
object Diff {

    enum class Kind { Same, Removed, Added }

    /**
     * @property oldNumber 在旧文本中的行号（从 1 开始）；新加的行为 null
     * @property newNumber 在新文本中的行号（从 1 开始）；删掉的行为 null
     */
    data class Line(
        val kind: Kind,
        val text: String,
        val oldNumber: Int?,
        val newNumber: Int?,
    )

    fun lines(old: String, new: String): List<Line> {
        val oldLines = splitLines(old)
        val newLines = splitLines(new)
        val deltas = DiffUtils.diff(oldLines, newLines).deltas.sortedBy { it.source.position }

        val result = ArrayList<Line>(maxOf(oldLines.size, newLines.size))
        var o = 0 // 下一行旧文本的下标
        var n = 0 // 下一行新文本的下标

        fun sameUntil(oldIndex: Int) {
            while (o < oldIndex) {
                result += Line(Kind.Same, oldLines[o], o + 1, n + 1)
                o++
                n++
            }
        }

        for (delta in deltas) {
            sameUntil(delta.source.position)
            if (delta.type == DeltaType.DELETE || delta.type == DeltaType.CHANGE) {
                for (text in delta.source.lines) {
                    result += Line(Kind.Removed, text, o + 1, null)
                    o++
                }
            }
            if (delta.type == DeltaType.INSERT || delta.type == DeltaType.CHANGE) {
                for (text in delta.target.lines) {
                    result += Line(Kind.Added, text, null, n + 1)
                    n++
                }
            }
        }
        sameUntil(oldLines.size)
        return result
    }

    /** 统计新增与删除的行数，用于「+3 −1」这样的摘要。 */
    fun summary(lines: List<Line>): Pair<Int, Int> =
        lines.count { it.kind == Kind.Added } to lines.count { it.kind == Kind.Removed }

    /** 按 \n、\r\n、\r 分行；末尾的一个换行不产生额外的空行；空文本没有行。 */
    internal fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lines()
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }
}
