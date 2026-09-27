package app.qichi.feature.reading

import app.qichi.shared.api.ReadingPrompt
import app.qichi.shared.rules.Limits
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 阅读的常用提示词（P14-05）：当场写的要求存成常用时的名字、调顺序。 */
class PromptListTest {
    private fun p(title: String) = ReadingPrompt(UUID.randomUUID(), title, "要求")

    @Test
    fun `存成常用时的名字：取第一行，长了截到 12 个字加省略号，不超过名字上限`() {
        assertEquals("翻译成英文", promptTitle("  翻译成英文  "))
        assertEquals("这段和我们最近的生活有什…", promptTitle("这段和我们最近的生活有什么呼应？说两三句就好"))
        assertEquals("先解释", promptTitle("先解释\n再翻译"))
        assertTrue(promptTitle("长".repeat(300)).length <= Limits.READING_PROMPT_TITLE_LENGTH.last)
    }

    @Test
    fun `调顺序：上移下移；到头了不动`() {
        val list = listOf(p("一"), p("二"), p("三"))
        assertEquals(listOf("二", "一", "三"), list.moved(1, 0).map { it.title })
        assertEquals(listOf("一", "三", "二"), list.moved(1, 2).map { it.title })
        assertSame(list, list.moved(0, -1))
        assertSame(list, list.moved(2, 3))
    }
}
