package app.qichi.server.review

import app.qichi.server.review.PdfPreview.Word
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.NormRect
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.TextBlock
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.ReviewFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPreviewTest {
    private fun w(text: String, x: Double, baseline: Double, size: Double = 12.0) =
        Word(text, x, baseline - size, x + text.length * size, baseline, size)

    @Test fun `中文换行并成一段不加空格；英文加空格；段落之间空得多就分开`() {
        val blocks = PdfPreview.group(listOf(
            w("标题", 72.0, 80.0),
            w("首付百分之三十，余款验收后付清。整个工期预计六周", 72.0, 100.0), w("如遇延迟双方协商。", 72.0, 118.0),
            w("Second paragraph starts here and it is long", 72.0, 160.0), w("enough", 72.0, 175.0),
        ), ReviewFormat.Text)
        // 标题没写满一行：自成一段，即使行距和正文一样
        assertEquals(listOf("标题", "首付百分之三十，余款验收后付清。整个工期预计六周如遇延迟双方协商。", "Second paragraph starts here and it is long enough"), blocks.map { it.text })
        assertTrue(blocks.all { it.kind == AnchorKind.Paragraph })
    }

    @Test fun `表格里上下相邻的窄格子不并成一段`() {
        val blocks = PdfPreview.group(listOf(w("项目", 72.0, 100.0), w("数量", 200.0, 100.0), w("橱柜", 72.0, 118.0), w("3", 200.0, 118.0)), ReviewFormat.Text)
        assertEquals(listOf("项目", "数量", "橱柜", "3"), blocks.map { it.text })
    }

    @Test fun `双栏：同一行中间空得很大就分成两段`() {
        val blocks = PdfPreview.group(listOf(w("Left", 72.0, 100.0), w("Right", 400.0, 100.0)), ReviewFormat.Pdf)
        assertEquals(listOf("Left", "Right"), blocks.map { it.text })
    }

    @Test fun `重新找位置：原文稍改也能找到，差太多算没找到；相似度对中文有效`() {
        fun page(n: Int, vararg texts: String) = ReviewPage(n, 595.0, 842.0, UUID.randomUUID(),
            texts.mapIndexed { i, t -> TextBlock("p$n-b${i + 1}", AnchorKind.Paragraph, NormRect(0.1, 0.1 * (i + 1), 0.5, 0.05), t) }, emptyList())
        val pages = listOf(page(1, "付款方式"), page(2, "首付百分之四十，余款验收后付清。"))
        val old = AnnotationAnchor(1, AnchorKind.Paragraph, null, "p1-b2", "首付百分之三十，余款验收后付清。")
        val (found, lost) = Relocate.anchor(old, pages)
        assertEquals(false, lost)
        assertEquals(2, found.page)
        assertEquals("p2-b1", found.ref)

        val (gone, lost2) = Relocate.anchor(old.copy(quote = "完全不相干的一句话"), pages)
        assertTrue(lost2)
        assertEquals(1, gone.page)
        assertTrue(Relocate.similarity("首付百分之三十", "首付百分之四十") > 0.6)
    }
}
