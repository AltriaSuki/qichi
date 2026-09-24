package app.qichi.core.ui

/**
 * 编辑器里格式按钮做的改动（P9-01）：都是纯文本操作，输入正文和选区，返回新的正文和选区。
 * 选区用 [start]、[end]（end ≥ start，相等表示光标）。
 */
object MarkdownEdits {
    data class Edit(val text: String, val start: Int, val end: Int = start)

    private val listPrefix = Regex("^(\\s*)(- \\[[ xX]] |[-*+] |\\d{1,3}[.)] |> )")
    private val headingPrefix = Regex("^(#{1,6})\\s+")

    /** 选区涉及的那几行：[首行开头, 末行结尾)。 */
    private fun lineRange(text: String, start: Int, end: Int): IntRange {
        val from = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0 || it < 0) 0 else it + 1 }
            .let { if (start > 0 && text.getOrNull(start - 1) == '\n') start else it }
        val to = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return from until to
    }

    /** 加粗：没选中就插入 **｜**；选中的已经是粗体（或被 ** 包着）就去掉，否则包上。 */
    fun toggleBold(e: Edit): Edit = wrap(e, "**")

    private fun wrap(e: Edit, mark: String): Edit {
        val t = e.text
        val n = mark.length
        if (e.start == e.end) {
            // 光标正好在一对空的 **｜** 中间：去掉
            if (t.startsWith(mark, e.start) && e.start >= n && t.startsWith(mark, e.start - n)) {
                return Edit(t.removeRange(e.start - n, e.start + n), e.start - n)
            }
            return Edit(t.substring(0, e.start) + mark + mark + t.substring(e.start), e.start + n)
        }
        val selected = t.substring(e.start, e.end)
        return when {
            selected.length >= 2 * n && selected.startsWith(mark) && selected.endsWith(mark) ->
                Edit(t.substring(0, e.start) + selected.substring(n, selected.length - n) + t.substring(e.end), e.start, e.end - 2 * n)
            e.start >= n && t.startsWith(mark, e.start - n) && t.startsWith(mark, e.end) ->
                Edit(t.substring(0, e.start - n) + selected + t.substring(e.end + n), e.start - n, e.end - n)
            else -> Edit(t.substring(0, e.start) + mark + selected + mark + t.substring(e.end), e.start + n, e.end + n)
        }
    }

    /** 标题：光标所在行在「无 → 一级 → 二级 → 无」之间轮换（去掉行首的列表、引用标记）。 */
    fun cycleHeading(e: Edit): Edit {
        val range = lineRange(e.text, e.start, e.start)
        val line = e.text.substring(range.first, range.last + 1)
        val level = headingPrefix.find(line)?.groupValues?.get(1)?.length ?: 0
        val content = line.replaceFirst(headingPrefix, "").replaceFirst(listPrefix, "")
        val next = when (level) {
            0 -> "# "
            1 -> "## "
            else -> ""
        }
        return replaceLines(e, range, listOf(next + content))
    }

    /**
     * 行首标记（「- 」列表、「- [ ] 」勾选框、「> 」引用）：选区涉及的每一行都已经有这个标记就去掉，
     * 否则都加上（原来的别种列表 / 引用标记换掉）。空行不加。
     */
    fun toggleLinePrefix(e: Edit, prefix: String): Edit {
        val range = lineRange(e.text, e.start, e.end)
        val lines = e.text.substring(range.first, range.last + 1).split('\n')
        val content = lines.filter { it.isNotBlank() }
        val allHave = content.isNotEmpty() && content.all { it.trimStart().startsWith(prefix) }
        val changed = lines.map { line ->
            when {
                line.isBlank() && lines.size > 1 -> line
                allHave -> line.replaceFirst(listPrefix, "$1")
                else -> {
                    val m = listPrefix.find(line)
                    val indent = m?.groupValues?.get(1) ?: line.takeWhile { it == ' ' }
                    val rest = if (m != null) line.substring(m.range.last + 1) else line.trimStart().replaceFirst(headingPrefix, "")
                    indent + prefix + rest
                }
            }
        }
        return replaceLines(e, range, changed)
    }

    /** 分隔线：在光标所在行后面另起一行插入「---」，光标放到分隔线下面的新行。 */
    fun insertRule(e: Edit): Edit = insertBlock(e, "---")

    /** 在光标所在行后面另起一行插入一整行（分隔线、图片），前后各空一行，光标放到它下面。 */
    fun insertBlock(e: Edit, block: String): Edit {
        val t = e.text
        val lineEnd = t.indexOf('\n', e.end).let { if (it < 0) t.length else it }
        val before = t.substring(0, lineEnd)
        val after = t.substring(lineEnd)
        val lead = when {
            before.isEmpty() -> ""
            before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val insert = lead + block + "\n\n"
        val rest = after.removePrefix("\n")
        return Edit(before + insert + rest, before.length + insert.length)
    }

    private val task = Regex("^(\\s*- \\[)([ xX])(] )")

    /** 预览里点勾选框：第 [line] 行（从 0 开始）的「- [ ] 」和「- [x] 」互换；那一行不是勾选框就不变。 */
    fun toggleTask(text: String, line: Int): String {
        val lines = text.split('\n').toMutableList()
        val current = lines.getOrNull(line) ?: return text
        val m = task.find(current) ?: return text
        val mark = if (m.groupValues[2] == " ") "x" else " "
        lines[line] = current.replaceRange(m.range, m.groupValues[1] + mark + m.groupValues[3])
        return lines.joinToString("\n")
    }

    /** 把 [range] 里的几行换成 [lines]。 */
    private fun replaceLines(e: Edit, range: IntRange, lines: List<String>): Edit {
        val t = e.text
        val newBlock = lines.joinToString("\n")
        val text = t.substring(0, range.first) + newBlock + t.substring(range.last + 1)
        val blockEnd = range.first + newBlock.length
        return if (e.start == e.end) {
            // 单个光标：停在这一行的末尾，接着写
            val cursorLine = lineRange(text, range.first, range.first)
            Edit(text, (cursorLine.last + 1).coerceIn(range.first, blockEnd))
        } else {
            // 选区：盖住改过的这几行（再点一次同一个按钮能原样改回去）
            Edit(text, range.first, blockEnd)
        }
    }
}
