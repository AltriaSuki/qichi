package app.qichi.feature.writing

import app.qichi.core.ui.Markdown
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentAnchorsTest {
    private val text = "# 海边周末\n\n周六早上八点出发，去东山岛。\n\n- [ ] 带外套\n\n---\n\n晚上找一家安静的小店吃海鲜。"
    private val blocks = Markdown.parse(text)

    @Test
    fun `原文还在：找到包含它的那一块（选中的一小段也行）`() {
        assertEquals(1, CommentAnchors.locate(blocks, "周六早上八点出发，去东山岛。"))
        assertEquals(4, CommentAnchors.locate(blocks, "安静的小店"))
        assertEquals(2, CommentAnchors.locate(blocks, "带外套"))
    }

    @Test
    fun `原文改了几个字：找最像的一块；改得面目全非就找不到`() {
        val edited = Markdown.parse(text.replace("周六早上八点出发，去东山岛。", "周六早上九点出发，去东山岛。"))
        assertEquals(1, CommentAnchors.locate(edited, "周六早上八点出发，去东山岛。"))
        val rewritten = Markdown.parse("# 海边周末\n\n改成下个月再去。")
        assertNull(CommentAnchors.locate(rewritten, "晚上找一家安静的小店吃海鲜。"))
    }

    @Test
    fun `分隔线和照片不能留言`() {
        assertNull(CommentAnchors.text(blocks[3]))
    }
}
