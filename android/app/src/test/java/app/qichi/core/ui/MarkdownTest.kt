package app.qichi.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class MarkdownTest {
    private val text = """
        ## 我们现在的样子

        窗边的绿萝又长了一截。
        你说要去看海。

        ### 明年想做的事
        - 把阳台改成能喝茶的地方
        2. 每个月留一个空周末
        > 慢慢来
        > 不着急
        ---
    """.trimIndent()

    @Test
    fun `按行分块：标题、段落（相邻行合并）、列表、连续引用合并、分隔线`() {
        val blocks = Markdown.parse(text)
        assertEquals(Markdown.Block.Heading(2, "我们现在的样子", 0), blocks[0])
        assertEquals(Markdown.Block.Paragraph("窗边的绿萝又长了一截。\n你说要去看海。", 2), blocks[1])
        assertEquals(Markdown.Block.Heading(3, "明年想做的事", 5), blocks[2])
        assertEquals(Markdown.Block.Item("·", "把阳台改成能喝茶的地方", 6), blocks[3])
        assertEquals(Markdown.Block.Item("2.", "每个月留一个空周末", 7), blocks[4])
        assertEquals(Markdown.Block.Quote("慢慢来\n不着急", 8), blocks[5])
        assertIs<Markdown.Block.Rule>(blocks[6])
        assertEquals(7, blocks.size)
    }

    @Test
    fun `大纲只取标题，并记下所在行`() {
        assertEquals(listOf("我们现在的样子" to 0, "明年想做的事" to 5), Markdown.headings(text).map { it.text to it.line })
    }

    @Test
    fun `行内粗体、斜体、代码去掉标记符号`() {
        assertEquals("一起看海和晚饭做汤", Markdown.inline("一起**看海**和*晚饭*做`汤`", Color.Gray).text)
    }

    @Test
    fun `编辑器着色不改变任何字符，标记淡化`() {
        val source = "## 标题\n- 一项\n正文"
        val styled = Markdown.highlight(source, Color.Gray, 22.sp)
        assertEquals(source, styled.text)
        val faded = styled.spanStyles.filter { it.item.color == Color.Gray }.map { source.substring(it.start, it.end) }
        assertEquals(listOf("## ", "- "), faded)
    }

    @Test
    fun `勾选框：认得出勾没勾，编辑时淡化整个标记`() {
        val blocks = Markdown.parse("要带：\n- [ ] 外套\n- [x] 相机\n- 普通一项")
        assertEquals(
            listOf(Markdown.Block.Task(false, "外套", 1), Markdown.Block.Task(true, "相机", 2), Markdown.Block.Item("·", "普通一项", 3)),
            blocks.drop(1),
        )
        val styled = Markdown.highlight("- [ ] 外套", Color.Gray, 20.sp)
        assertEquals(listOf("- [ ] "), styled.spanStyles.filter { it.item.color == Color.Gray }.map { "- [ ] 外套".substring(it.start, it.end) })
    }

    @Test
    fun `照片行：认出文件和说明，编辑时整行淡化；不是单独一行的不算`() {
        val id = java.util.UUID.fromString("01a0c31e-bdff-73fd-ba1e-305adb764467")
        val text = "看日落\n![日落](qichi-file:$id)\n文字里 ![x](qichi-file:$id) 不算"
        val blocks = Markdown.parse(text)
        assertEquals(Markdown.Block.Image(id, "日落", 1), blocks[1])
        assertTrue(blocks[2] is Markdown.Block.Paragraph)
        val styled = Markdown.highlight("![日落](qichi-file:$id)", Color.Gray, 20.sp)
        assertEquals(listOf("![日落](qichi-file:$id)"), styled.spanStyles.filter { it.item.color == Color.Gray }.map { styled.text.substring(it.start, it.end) })
    }

    @Test
    fun `代码块原样保留，里面的符号不当标记；没写完的代码块算到结尾`() {
        val blocks = Markdown.parse("看这段：\n```kotlin\nval a = **b**\n# 不是标题\n```\n结束")
        assertEquals(Markdown.Block.Code("val a = **b**\n# 不是标题", 1), blocks[1])
        assertEquals(Markdown.Block.Paragraph("结束", 5), blocks[2])
        assertEquals(listOf(Markdown.Block.Code("写到一半", 0)), Markdown.parse("```\n写到一半"))
    }

    @Test
    fun `表格：表头、分隔行、行；格子不够的补空`() {
        val blocks = Markdown.parse("| 地方 | 预算 |\n|---|:--:|\n| 海边 | 3000 |\n| 山里 |\n之后")
        assertEquals(Markdown.Block.Table(listOf("地方", "预算"), listOf(listOf("海边", "3000"), listOf("山里", "")), 0), blocks[0])
        assertEquals(Markdown.Block.Paragraph("之后", 4), blocks[1])
    }

    @Test
    fun `没有分隔行的竖线不算表格`() {
        assertIs<Markdown.Block.Paragraph>(Markdown.parse("| 只是一行 |")[0])
    }

    @Test
    fun `嵌套列表记下缩进`() {
        val blocks = Markdown.parse("- 周末\n  - 买菜\n    1. 番茄")
        assertEquals(listOf(0, 1, 2), blocks.map { (it as Markdown.Block.Item).indent })
    }

    @Test
    fun `行内链接、删除线去掉符号，链接能点`() {
        val styled = Markdown.inline("看[这里](https://example.com)，~~不去了~~", Color.Gray)
        assertEquals("看这里，不去了", styled.text)
        assertEquals(1, styled.getLinkAnnotations(0, styled.length).size)
    }

    @Test
    fun `普通文字交给 plain 追加，粗体里的也是`() {
        val styled = Markdown.inline("见[1]和**重点[2]**", Color.Gray) { append(it.replace(Regex("\\[\\d]"), "#")) }
        assertEquals("见#和重点#", styled.text)
    }

    @Test
    fun `纯文字：去掉所有标记`() {
        val text = "## 建议\n\n- **早点**出门\n- 带`伞`\n\n> 慢慢来"
        assertEquals("建议\n· 早点出门\n· 带伞\n慢慢来", Markdown.plain(text))
    }
}
