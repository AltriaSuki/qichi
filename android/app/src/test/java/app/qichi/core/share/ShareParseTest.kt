package app.qichi.core.share

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 从别的 App 分享进来的内容怎么取（P16-03）。 */
class ShareParseTest {
    @Test
    fun `分享一段文字或链接：标题不在正文里时放在前面`() {
        assertEquals(Shared("周末去看海", emptyList<String>()), parseShare(ACTION_SEND, "text/plain", null, "  周末去看海 ", emptyList<String>()))
        assertEquals(
            Shared("一家好吃的面馆\nhttps://example.com/a", emptyList<String>()),
            parseShare(ACTION_SEND, "text/plain", "一家好吃的面馆", "https://example.com/a", emptyList<String>()),
        )
        // 有的 App 把标题也写进了正文
        assertEquals("面馆 https://example.com/a", parseShare(ACTION_SEND, "text/plain", "面馆", "面馆 https://example.com/a", emptyList<String>())!!.text)
        assertEquals("只有标题", parseShare(ACTION_SEND, "text/plain", "只有标题", null, emptyList<String>())!!.text)
    }

    @Test
    fun `分享照片：只收图片，最多 9 张，可以带一句话`() {
        val nine = (1..12).map { "content://p/$it" }
        val many = parseShare(ACTION_SEND_MULTIPLE, "image/*", null, null, nine)!!
        assertEquals(9, many.images.size)
        assertNull(many.text)
        assertEquals(Shared("好看", listOf("content://p/1")), parseShare(ACTION_SEND, "image/jpeg", null, "好看", listOf("content://p/1")))
        // 不是图片的文件不收；什么都没剩就是 null
        assertNull(parseShare(ACTION_SEND, "application/pdf", null, null, listOf("content://f/1")))
    }

    @Test
    fun `不是分享、或者什么都没有：null`() {
        assertNull(parseShare("android.intent.action.VIEW", "text/plain", null, "x", emptyList<String>()))
        assertNull(parseShare(ACTION_SEND, "text/plain", "  ", "   ", emptyList<String>()))
    }

    @Test
    fun `太长的文字截到一条消息的上限`() {
        val long = "字".repeat(20_000)
        assertEquals(app.qichi.shared.rules.Limits.MESSAGE_BODY_MAX, parseShare(ACTION_SEND, "text/plain", null, long, emptyList<String>())!!.text!!.length)
    }
}
