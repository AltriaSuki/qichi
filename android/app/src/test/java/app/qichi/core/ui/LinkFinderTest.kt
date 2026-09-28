package app.qichi.core.ui

import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 聊天里的网址能点：找网址的规则，和「最近一条」的日期说法。 */
class LinkFinderTest {
    private fun urls(text: String) = findLinks(text).map { it.url }
    private fun spans(text: String) = findLinks(text).map { text.substring(it.start, it.end) }

    @Test
    fun `http、https、www 开头的都算，www 补上 https`() {
        assertEquals(listOf("https://example.com/a?b=1"), urls("看看 https://example.com/a?b=1"))
        assertEquals(listOf("http://a.cn"), urls("http://a.cn"))
        assertEquals(listOf("https://www.xiaohongshu.com/x"), urls("www.xiaohongshu.com/x"))
    }

    @Test
    fun `紧挨着的中文和全角标点不算网址`() {
        assertEquals(listOf("https://a.cn/x"), spans("看这个https://a.cn/x很好"))
        assertEquals(listOf("https://a.cn/x"), spans("链接：https://a.cn/x，明天去"))
        assertEquals(listOf("https://a.cn/x"), spans("（https://a.cn/x）"))
    }

    @Test
    fun `句末的英文标点去掉，成对的括号留着`() {
        assertEquals(listOf("https://a.cn/x"), spans("see https://a.cn/x."))
        assertEquals(listOf("https://a.cn/x"), spans("(https://a.cn/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), spans("https://en.wikipedia.org/wiki/Foo_(bar)"))
    }

    @Test
    fun `几个网址都找到，位置对得上`() {
        val text = "a https://a.cn b www.b.cn"
        val found = findLinks(text)
        assertEquals(2, found.size)
        assertEquals("https://a.cn", text.substring(found[0].start, found[0].end))
        assertEquals("www.b.cn", text.substring(found[1].start, found[1].end))
    }

    @Test
    fun `不是网址的不算`() {
        assertEquals(emptyList(), urls("https://"))
        assertEquals(emptyList(), urls("www."))
        assertEquals(emptyList(), urls("abchttps://a.cn"))
        assertEquals(emptyList(), urls("今天去了海边"))
        assertEquals(emptyList(), urls("https://localhost"))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 28)
    private fun at(date: LocalDate, h: Int, m: Int): Instant = date.atTime(h, m).atZone(zone).toInstant()

    @Test
    fun `今天只写时刻，昨天写昨天，更早写日期`() {
        assertEquals("19:30", dayTime(at(today, 19, 30), zone, today))
        assertEquals("昨天 08:05", dayTime(at(today.minusDays(1), 8, 5), zone, today))
        assertEquals("09.21 19:30", dayTime(at(LocalDate.of(2026, 9, 21), 19, 30), zone, today))
        assertEquals("2025.12.31 23:59", dayTime(at(LocalDate.of(2025, 12, 31), 23, 59), zone, today))
    }

    @Test
    fun `今天和昨天算最近`() {
        assertTrue(isRecent(at(today, 0, 1), zone, today))
        assertTrue(isRecent(at(today.minusDays(1), 0, 1), zone, today))
        assertFalse(isRecent(at(today.minusDays(2), 23, 59), zone, today))
    }
}
