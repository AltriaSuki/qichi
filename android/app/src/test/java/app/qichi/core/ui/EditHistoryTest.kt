package app.qichi.core.ui

import app.qichi.core.ui.EditHistory.State
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {
    private var clock = 0L
    private val h = EditHistory(groupMillis = 1_000, now = { clock })

    /** 模拟一个字一个字地打，每字间隔 [gap] 毫秒；返回最后的样子。 */
    private fun type(from: State, text: String, gap: Long = 100): State {
        var s = from
        for (c in text) {
            clock += gap
            val next = State(s.text.substring(0, s.start) + c + s.text.substring(s.start), s.start + 1)
            h.record(s, next)
            s = next
        }
        return s
    }

    @Test
    fun `连续打字合成一步；停顿之后另起一步`() {
        var s = type(State("", 0), "周六出发")
        clock += 3_000
        s = type(s, "，带外套")
        assertEquals("周六出发，带外套", s.text)
        val first = h.undo(s)!!
        assertEquals("周六出发", first.text)
        val second = h.undo(first)!!
        assertEquals("", second.text)
        assertNull(h.undo(second))
    }

    @Test
    fun `重做能恢复；撤销后再改就清掉重做`() {
        val s = type(State("", 0), "海边")
        val back = h.undo(s)!!
        assertTrue(h.canRedo)
        assertEquals("海边", h.redo(back)!!.text)
        val again = h.undo(s)!!
        clock += 5_000
        type(again, "山里")
        assertFalse(h.canRedo)
    }

    @Test
    fun `格式按钮总是单独一步；打字换成删除也另起一步`() {
        var s = type(State("", 0), "外套")
        val bold = State("**外套**", 2, 4)
        h.record(s, bold, force = true)
        s = bold
        assertEquals("外套", h.undo(s)!!.text, "撤销只退掉加粗")

        val h2 = EditHistory(now = { clock })
        var t = State("", 0)
        for (c in "相机") { clock += 50; val n = State(t.text + c, t.start + 1); h2.record(t, n); t = n }
        clock += 50
        val deleted = State("相", 1)
        h2.record(t, deleted)
        assertEquals("相机", h2.undo(deleted)!!.text, "删除是新的一步")
    }

    @Test
    fun `外部换了正文就清空`() {
        type(State("", 0), "草稿")
        h.clear()
        assertFalse(h.canUndo)
    }
}
