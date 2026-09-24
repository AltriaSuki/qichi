package app.qichi.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals
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
}
