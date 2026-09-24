package app.qichi.core.ui

import app.qichi.core.ui.MarkdownEdits.Edit
import org.junit.Test
import kotlin.test.assertEquals

class MarkdownEditsTest {
    /** 用「|」标光标，「[」「]」标选区，方便读。 */
    private fun e(marked: String): Edit {
        val s = marked.indexOf('[')
        return if (s >= 0) {
            val end = marked.indexOf(']') - 1
            Edit(marked.replace("[", "").replace("]", ""), s, end)
        } else {
            val c = marked.indexOf('|')
            Edit(marked.replace("|", ""), c)
        }
    }

    private fun show(x: Edit): String =
        if (x.start == x.end) x.text.substring(0, x.start) + "|" + x.text.substring(x.start)
        else x.text.substring(0, x.start) + "[" + x.text.substring(x.start, x.end) + "]" + x.text.substring(x.end)

    @Test
    fun `加粗：选中的包上、再点去掉；没选中插一对、光标在中间`() {
        assertEquals("周六**[出发]**", show(MarkdownEdits.toggleBold(e("周六[出发]"))))
        assertEquals("周六[出发]", show(MarkdownEdits.toggleBold(e("周六**[出发]**"))))
        assertEquals("周六[出发]", show(MarkdownEdits.toggleBold(e("周六[**出发**]"))))
        assertEquals("周六**|**", show(MarkdownEdits.toggleBold(e("周六|"))))
        assertEquals("周六|", show(MarkdownEdits.toggleBold(e("周六**|**"))))
    }

    @Test
    fun `标题：无、一级、二级、无轮换，只动光标所在行`() {
        val one = MarkdownEdits.cycleHeading(e("第一行\n海边|的旅店\n第三行"))
        assertEquals("第一行\n# 海边的旅店|\n第三行", show(one))
        val two = MarkdownEdits.cycleHeading(one)
        assertEquals("第一行\n## 海边的旅店|\n第三行", show(two))
        assertEquals("第一行\n海边的旅店|\n第三行", show(MarkdownEdits.cycleHeading(two)))
        assertEquals("# 带外套|", show(MarkdownEdits.cycleHeading(e("- 带|外套"))), "列表行变标题时去掉列表标记")
    }

    @Test
    fun `列表、勾选框、引用：选中的每行都加上；都有了就去掉；换掉别种标记`() {
        assertEquals("[- 外套\n- 相机]", show(MarkdownEdits.toggleLinePrefix(e("[外套\n相机]"), "- ")))
        assertEquals("[外套\n相机]", show(MarkdownEdits.toggleLinePrefix(e("[- 外套\n- 相机]"), "- ")))
        assertEquals("- [ ] 外套|", show(MarkdownEdits.toggleLinePrefix(e("- 外|套"), "- [ ] ")))
        assertEquals("> 雨是从傍晚开始下的|", show(MarkdownEdits.toggleLinePrefix(e("雨是从|傍晚开始下的"), "> ")))
        assertEquals("[- a\n\n- b]", show(MarkdownEdits.toggleLinePrefix(e("[a\n\nb]"), "- ")), "中间的空行不加")
        assertEquals("- |", show(MarkdownEdits.toggleLinePrefix(e("|"), "- ")), "空文稿里点列表就开始一个列表")
    }

    @Test
    fun `分隔线：另起一行插入，前后空行，光标到下面`() {
        assertEquals("第一段\n\n---\n\n|", show(MarkdownEdits.insertRule(e("第一|段"))))
        assertEquals("第一段\n\n---\n\n|第二段", show(MarkdownEdits.insertRule(e("第一|段\n第二段"))))
        assertEquals("---\n\n|", show(MarkdownEdits.insertRule(e("|"))))
    }

    @Test
    fun `预览里点勾选框：只改那一行`() {
        val text = "要带：\n- [ ] 外套\n- [x] 相机"
        assertEquals("要带：\n- [x] 外套\n- [x] 相机", MarkdownEdits.toggleTask(text, 1))
        assertEquals("要带：\n- [ ] 外套\n- [ ] 相机", MarkdownEdits.toggleTask(text, 2))
        assertEquals(text, MarkdownEdits.toggleTask(text, 0), "不是勾选框的行不变")
    }
}
