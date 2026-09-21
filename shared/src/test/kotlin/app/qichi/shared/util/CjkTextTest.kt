package app.qichi.shared.util

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class CjkTextTest {

    private fun count(text: String) = CjkText.charCount(text)

    @Test
    fun `空文本与只有空白为 0`() {
        assertEquals(0, count(""))
        assertEquals(0, count("   \n\t　"))
    }

    @Test
    fun `纯中文每个汉字算 1，标点不算`() {
        assertEquals(15, count("今天连开了三个会，晚上想安静待着。"))
        assertEquals(8, count("周六早上八点出发？"))
        assertEquals(2, count("你好！！！……——"))
        assertEquals(3, count("「栖迟」《书》"))
    }

    @Test
    fun `中英混排：汉字按字，英文按词`() {
        assertEquals(6, count("我用 Kotlin 写 Android App"))
        assertEquals(6, count("我用Kotlin写Android App"))
        assertEquals(2, count("Hello, world."))
        assertEquals(2, count("iPhone15 Pro"))
    }

    @Test
    fun `数字连在一起算 1，包括小数与千分位`() {
        assertEquals(6, count("2026年9月21日"))
        assertEquals(4, count("3.14 和 1,000 元"))
        assertEquals(1, count("１２３"))
    }

    @Test
    fun `撇号与连字符连接的算一个词，放在词尾的不算`() {
        assertEquals(2, count("don't well-known"))
        assertEquals(1, count("it’s"))
        assertEquals(1, count("end."))
        assertEquals(2, count("a - b"))
    }

    @Test
    fun `表情与符号不算`() {
        assertEquals(1, count("😀👍🏻 好"))
        assertEquals(0, count("→ ★ © ~ @#%"))
    }

    @Test
    fun `假名与韩文按字计`() {
        assertEquals(5, count("こんにちは"))
        assertEquals(5, count("안녕하세요"))
        assertEquals(3, count("カタカナ".substring(0, 3)))
    }

    @Test
    fun `分解形式的重音字母不打断单词`() {
        val nfd = Normalizer.normalize("café naïve", Normalizer.Form.NFD)
        assertEquals(2, count(nfd))
    }

    @Test
    fun `Markdown 标记不算字`() {
        assertEquals(4, count("## 标题\n\n- **重点**"))
    }

    @Test
    fun `阅读时长按每分钟 400 字向上取整`() {
        assertEquals(0, CjkText.readingMinutes(""))
        assertEquals(1, CjkText.readingMinutes("好"))
        assertEquals(1, CjkText.readingMinutes("字".repeat(400)))
        assertEquals(2, CjkText.readingMinutes("字".repeat(401)))
        assertEquals(3, CjkText.readingMinutes(1_200))
    }
}
