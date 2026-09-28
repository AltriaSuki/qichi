package app.qichi.feature.reading

import app.qichi.core.data.ReadingSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 书内阅读的本机设置（P20-01）换成 Readium 的排版设置；目录里标出正在读的那一章。 */
class ReaderPrefsTest {
    @Test
    fun `默认照原书排：不改行距、不关原书样式，字号跟着大字`() {
        val p = epubPreferences(ReadingSettings(), paper = 1, ink = 2, appScale = 1.2f)
        assertNull(p.lineHeight)
        assertNull(p.publisherStyles)
        assertEquals(1.2, p.fontSize!!, 1e-6)
        assertEquals(1.0, p.pageMargins!!, 1e-6)
        assertEquals(false, p.scroll)
    }

    @Test
    fun `选了行距才关原书样式；字号乘在大字上；滚动和页边距照设置`() {
        val p = epubPreferences(ReadingSettings(fontScale = 1.3f, lineHeight = 2f, margins = .6f, scroll = true), 1, 2, appScale = 1.15f)
        assertEquals(2.0, p.lineHeight!!, 1e-6)
        assertFalse(p.publisherStyles!!)
        assertEquals(1.3 * 1.15, p.fontSize!!, 1e-4)
        assertEquals(.6, p.pageMargins!!, 1e-6)
        assertEquals(true, p.scroll)
    }

    @Test
    fun `正在读的章：同一个文件的第一项，不看锚点`() {
        val toc = listOf("text/cover.xhtml", "text/ch1.xhtml", "text/ch1.xhtml#s2", "text/ch2.xhtml#top")
        assertEquals(1, currentTocIndex(toc, "text/ch1.xhtml"))
        assertEquals(3, currentTocIndex(toc, "text/ch2.xhtml"))
        assertEquals(1, currentTocIndex(toc, "text/ch1.xhtml#s2"))
        assertEquals(-1, currentTocIndex(toc, "text/notes.xhtml"))
        assertEquals(-1, currentTocIndex(toc, null))
        assertEquals(-1, currentTocIndex(toc, ""))
    }
}
