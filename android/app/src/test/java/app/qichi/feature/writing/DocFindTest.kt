package app.qichi.feature.writing

import org.junit.Test
import kotlin.test.assertEquals

/** 文稿里查找、替换（P20-04）。 */
class DocFindTest {
    @Test
    fun `找到的位置：不分大小写、不重叠，空的不找`() {
        assertEquals(listOf(0..1, 5..6), DocFind.matches("我们去海边我们", "我们"))
        assertEquals(listOf(0..3, 5..8), DocFind.matches("Tofu tofu", "tofu"))
        assertEquals(listOf(0..1, 2..3), DocFind.matches("aaaa", "aa"))
        assertEquals(emptyList(), DocFind.matches("随便", ""))
        assertEquals(emptyList(), DocFind.matches("短", "很长的词"))
    }

    @Test
    fun `从光标往后找第一处，后面没有就回到第一处`() {
        val m = listOf(0..1, 5..6, 10..11)
        assertEquals(1, DocFind.nearest(m, 3))
        assertEquals(1, DocFind.nearest(m, 5))
        assertEquals(0, DocFind.nearest(m, 20))
        assertEquals(-1, DocFind.nearest(emptyList(), 0))
    }

    @Test
    fun `替换一处和全部替换`() {
        assertEquals("我们去山里我们", DocFind.replaceAt("我们去海边我们", 3..4, "山里"))
        assertEquals("咱俩去海边咱俩" to 2, DocFind.replaceAll("我们去海边我们", "我们", "咱俩"))
        assertEquals("x Y x" to 2, DocFind.replaceAll("Tofu Y tofu", "tofu", "x"))
        assertEquals("不变" to 0, DocFind.replaceAll("不变", "没有", "x"))
    }
}
