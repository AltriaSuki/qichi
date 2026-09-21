package app.qichi.shared.util

import app.qichi.shared.util.Diff.Kind.Added
import app.qichi.shared.util.Diff.Kind.Removed
import app.qichi.shared.util.Diff.Kind.Same
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    private fun render(old: String, new: String): List<String> =
        Diff.lines(old, new).map {
            val mark = when (it.kind) { Same -> " "; Removed -> "-"; Added -> "+" }
            "$mark${it.oldNumber ?: "_"}:${it.newNumber ?: "_"} ${it.text}"
        }

    @Test
    fun `完全相同时每行都是 Same`() {
        val lines = Diff.lines("一\n二\n三", "一\n二\n三")
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.kind == Same })
        assertEquals(listOf(1, 2, 3), lines.map { it.newNumber })
    }

    @Test
    fun `新增一行`() {
        assertEquals(
            listOf(" 1:1 一", "+_:2 二", " 2:3 三"),
            render("一\n三", "一\n二\n三"),
        )
    }

    @Test
    fun `删除一行`() {
        assertEquals(
            listOf(" 1:1 一", "-2:_ 二", " 3:2 三"),
            render("一\n二\n三", "一\n三"),
        )
    }

    @Test
    fun `修改一行表示为先删后加`() {
        assertEquals(
            listOf(" 1:1 秋天去一次海边", "-2:_ 周六出发", "+_:2 周日出发", " 3:3 带上外套"),
            render("秋天去一次海边\n周六出发\n带上外套", "秋天去一次海边\n周日出发\n带上外套"),
        )
    }

    @Test
    fun `多处改动的行号都正确`() {
        val old = "a\nb\nc\nd\ne\nf"
        val new = "a\nB\nc\nd\nf\ng"
        assertEquals(
            listOf(" 1:1 a", "-2:_ b", "+_:2 B", " 3:3 c", " 4:4 d", "-5:_ e", " 6:5 f", "+_:6 g"),
            render(old, new),
        )
        assertEquals(2 to 2, Diff.summary(Diff.lines(old, new)))
    }

    @Test
    fun `从空文本开始与清空文本`() {
        assertEquals(listOf("+_:1 第一行", "+_:2 第二行"), render("", "第一行\n第二行\n"))
        assertEquals(listOf("-1:_ 第一行"), render("第一行", ""))
        assertEquals(emptyList(), render("", ""))
    }

    @Test
    fun `末尾换行和 Windows 换行不算差异`() {
        assertTrue(Diff.lines("一\n二", "一\n二\n").all { it.kind == Same })
        assertTrue(Diff.lines("一\r\n二", "一\n二").all { it.kind == Same })
    }

    @Test
    fun `中间的空行照常对比`() {
        assertEquals(
            listOf(" 1:1 标题", "+_:2 ", " 2:3 正文"),
            render("标题\n正文", "标题\n\n正文"),
        )
    }
}
