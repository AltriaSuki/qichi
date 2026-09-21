package app.qichi.shared.rules

import app.qichi.shared.model.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageRulesTest {

    @Test
    fun `摘要取前 60 个字，空白合并`() {
        val long = "一".repeat(80)
        assertEquals("一".repeat(60), MessageRules.replyExcerpt(MessageKind.Text, long, null, retracted = false))
        assertEquals("周末 去河边", MessageRules.replyExcerpt(MessageKind.Text, "  周末\n\n 去河边 ", null, retracted = false))
    }

    @Test
    fun `不会把表情切成两半`() {
        val text = "好".repeat(59) + "😊😊"
        val excerpt = MessageRules.replyExcerpt(MessageKind.Text, text, null, retracted = false)!!
        assertEquals("好".repeat(59) + "😊", excerpt)
    }

    @Test
    fun `图片、文件没有正文时用占位；撤回的没有摘要`() {
        assertEquals("[图片]", MessageRules.replyExcerpt(MessageKind.Image, "", null, retracted = false))
        assertEquals("[文件] 行程.pdf", MessageRules.replyExcerpt(MessageKind.File, " ", "行程.pdf", retracted = false))
        assertEquals("看这张", MessageRules.replyExcerpt(MessageKind.Image, "看这张", null, retracted = false))
        assertNull(MessageRules.replyExcerpt(MessageKind.Text, "原文", null, retracted = true))
    }

    @Test
    fun `搜索词`() {
        assertEquals("河边", MessageRules.searchQuery("  河边 "))
        assertNull(MessageRules.searchQuery("   "))
        assertNull(MessageRules.searchQuery("字".repeat(101)))
    }
}
