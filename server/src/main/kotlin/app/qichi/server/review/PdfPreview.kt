package app.qichi.server.review

import app.qichi.shared.api.NormRect
import app.qichi.shared.api.TextBlock
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.ReviewFormat
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.DrawObject
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 给人看的失败原因（预览标成失败，不再重试）。 */
class PreviewFailure(message: String) : Exception(message)

/**
 * 把 PDF 按页做成「安全预览」：每页一张 JPEG，加上文字层（段落 / 单元格和它们在页面上的位置）与图片区域。
 * 手机上只看这些，不打开原文件。
 */
object PdfPreview {
    const val DPI = 144f

    class Page(
        val width: Double,
        val height: Double,
        val jpeg: ByteArray,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val blocks: List<TextBlock>,
        val images: List<NormRect>,
    )

    /** 逐页处理（不把所有页一起放进内存）。返回页数。 */
    fun render(pdf: Path, format: ReviewFormat, maxPages: Int, onPage: (Int, Page) -> Unit): Int {
        val doc = try {
            Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())
        } catch (_: InvalidPasswordException) {
            throw PreviewFailure("文件设了密码，打不开")
        } catch (e: java.io.IOException) {
            throw PreviewFailure("文件好像坏了，打不开")
        }
        doc.use {
            val count = doc.numberOfPages
            if (count == 0) throw PreviewFailure("文件里没有内容")
            if (count > maxPages) throw PreviewFailure("超过 $maxPages 页，太长了，请拆开再传")
            val renderer = PDFRenderer(doc)
            for (i in 0 until count) {
                val page = doc.getPage(i)
                val (w, h) = pageSize(page)
                val image = renderer.renderImageWithDPI(i, DPI, ImageType.RGB)
                val blocks = extractBlocks(doc, i + 1, w, h, format)
                val images = if (page.rotation % 360 == 0) ImageLocator(page).locate() else emptyList()
                onPage(i + 1, Page(w, h, jpeg(image), image.width, image.height, blocks, images))
            }
            return count
        }
    }

    /** 转过的页（90° / 270°）宽高互换，与渲染出的图片方向一致。 */
    private fun pageSize(page: PDPage): Pair<Double, Double> {
        val box = page.cropBox
        val rotated = (page.rotation / 90) % 2 != 0
        return if (rotated) box.height.toDouble() to box.width.toDouble() else box.width.toDouble() to box.height.toDouble()
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.85f
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    // ── 文字层 ──

    /** 一个词（PDFBox 按间隔切开的一段文字），坐标以页面左上角为原点，单位 pt。 */
    internal data class Word(val text: String, val x0: Double, val top: Double, val x1: Double, val bottom: Double, val size: Double) {
        val baseline get() = bottom
    }

    private class Collector(private val splitGap: Double) : PDFTextStripper() {
        val words = mutableListOf<Word>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, positions: List<TextPosition>) {
            // PDFBox 会把挨得近的几段拼成一个词；按字形之间的空隙再切开（表格里相邻的单元格常被拼在一起）
            var run = mutableListOf<TextPosition>()
            fun flush() {
                val chars = run.filter { it.unicode.isNotBlank() }
                if (chars.isNotEmpty()) words += word(chars)
                run = mutableListOf()
            }
            for (pos in positions) {
                val prev = run.lastOrNull()
                if (pos.unicode.isBlank()) {
                    flush()
                    continue
                }
                if (prev != null && pos.xDirAdj - (prev.xDirAdj + prev.widthDirAdj) > splitGap * max(pos.fontSizeInPt, prev.fontSizeInPt)) flush()
                run += pos
            }
            flush()
        }

        private fun word(chars: List<TextPosition>): Word {
            val x0 = chars.minOf { it.xDirAdj.toDouble() }
            val x1 = chars.maxOf { (it.xDirAdj + it.widthDirAdj).toDouble() }
            val baseline = chars.maxOf { it.yDirAdj.toDouble() }
            val size = chars.maxOf { it.fontSizeInPt.toDouble() }.coerceAtLeast(1.0)
            return Word(chars.joinToString("") { it.unicode }, x0, baseline - size * 0.8, x1, baseline, size)
        }
    }

    private fun extractBlocks(doc: PDDocument, pageNo: Int, width: Double, height: Double, format: ReviewFormat): List<TextBlock> {
        // 表格里数字靠右对齐，和右边格子只隔一点点
        val collector = Collector(if (format == ReviewFormat.Sheet) 0.1 else 0.2).apply {
            startPage = pageNo
            endPage = pageNo
        }
        collector.writeText(doc, Writer.nullWriter())
        return group(collector.words, format, width).mapIndexed { i, b ->
            TextBlock("p$pageNo-b${i + 1}", b.kind, b.rect.normalized(width, height), b.text)
        }
    }

    internal data class Rect(val x0: Double, val top: Double, val x1: Double, val bottom: Double) {
        fun union(o: Rect) = Rect(min(x0, o.x0), min(top, o.top), max(x1, o.x1), max(bottom, o.bottom))
        fun normalized(w: Double, h: Double): NormRect {
            val nx = (x0 / w).coerceIn(0.0, 1.0)
            val ny = (top / h).coerceIn(0.0, 1.0)
            return NormRect(nx, ny, ((x1 / w).coerceIn(0.0, 1.0) - nx).coerceAtLeast(0.0), ((bottom / h).coerceIn(0.0, 1.0) - ny).coerceAtLeast(0.0))
        }
    }

    internal data class Block(val kind: AnchorKind, val rect: Rect, val text: String)

    private class Segment(val words: List<Word>) {
        val rect = words.map { Rect(it.x0, it.top, it.x1, it.bottom) }.reduce(Rect::union)
        val size = words.maxOf { it.size }
        val text = words.fold("") { acc, w -> join(acc, w.text, " ") }
    }

    private class Paragraph(first: Segment) {
        var rect = first.rect
        var last = first
        var size = first.size
        var text = first.text
        fun add(s: Segment) {
            rect = rect.union(s.rect)
            last = s
            size = max(size, s.size)
            text = join(text, s.text, " ")
        }
    }

    /**
     * 词 → 行 → 段（按大的横向间隔切开）→ 表格里每段就是一个单元格；其它文件把上下挨着、左右重叠、字号相近的段并成一段落。
     */
    internal fun group(words: List<Word>, format: ReviewFormat, pageWidth: Double = 595.0): List<Block> {
        if (words.isEmpty()) return emptyList()
        // 行：按基线聚在一起
        val lines = mutableListOf<MutableList<Word>>()
        for (w in words.sortedWith(compareBy({ it.baseline }, { it.x0 }))) {
            val line = lines.lastOrNull()
            if (line != null && abs(line.last().baseline - w.baseline) < 0.6 * max(w.size, line.maxOf { it.size })) line += w else lines += mutableListOf(w)
        }
        // 段：行内按大的空隙切开（表格的列、双栏）
        val gapFactor = if (format == ReviewFormat.Sheet) 0.12 else 2.0
        val segments = lines.flatMap { line ->
            val sorted = line.sortedBy { it.x0 }
            val parts = mutableListOf(mutableListOf(sorted.first()))
            for (w in sorted.drop(1)) {
                val prev = parts.last().last()
                if (w.x0 - prev.x1 > gapFactor * max(w.size, prev.size)) parts += mutableListOf(w) else parts.last() += w
            }
            parts.map(::Segment)
        }
        if (format == ReviewFormat.Sheet) {
            return segments.map { Block(AnchorKind.Cell, it.rect, it.text) }
        }
        // 段落：下一行紧接着（行距不到字号的 2.3 倍，中文 1.5 倍行距约 1.9）、左右重叠、字号相近，并且上一行够宽、写满了（没写满说明段落在那里结束）
        val paragraphs = mutableListOf<Paragraph>()
        for (s in segments) {
            val target = paragraphs.lastOrNull { p ->
                val pitch = s.words.first().baseline - p.last.words.first().baseline
                val overlap = min(s.rect.x1, p.last.rect.x1) - max(s.rect.x0, p.last.rect.x0)
                val right = max(p.rect.x1, s.rect.x1)
                val width = right - min(p.rect.x0, s.rect.x0)
                val lastLineFull = p.last.rect.x1 >= right - max(4 * s.size, 0.12 * width)
                // 表格里上下相邻的窄格子不并
                val wide = p.last.rect.x1 - p.last.rect.x0 >= 0.3 * pageWidth
                wide && pitch > 0 && pitch < 2.3 * max(s.size, p.size) && overlap > 0 && lastLineFull &&
                    max(s.size, p.size) / min(s.size, p.size) < 1.3
            }
            if (target != null) target.add(s) else paragraphs += Paragraph(s)
        }
        // 保持阅读顺序（按每段第一行出现的先后）
        return paragraphs.map { Block(AnchorKind.Paragraph, it.rect, it.text) }
    }

    /** 拼接两段文字：两边都是中日韩文字时不加空格。 */
    internal fun join(a: String, b: String, sep: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (isCjk(a.last()) || isCjk(b.first())) a + b else a + sep + b
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA
    }

    // ── 图片区域 ──

    /** 找出页面上画了图片的位置（不含很小的图标和铺满整页的底图）。 */
    private class ImageLocator(private val page: PDPage) : PDFStreamEngine() {
        private val found = mutableListOf<NormRect>()

        init {
            addOperator(Concatenate(this))
            addOperator(DrawObject(this))
            addOperator(SetGraphicsStateParameters(this))
            addOperator(Save(this))
            addOperator(Restore(this))
            addOperator(SetMatrix(this))
        }

        fun locate(): List<NormRect> {
            runCatching { processPage(page) }
            return found
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName ?: return
                when (val obj = resources?.getXObject(name)) {
                    is PDImageXObject -> record()
                    is PDFormXObject -> showForm(obj)
                    else -> Unit
                }
            } else {
                super.processOperator(operator, operands)
            }
        }

        private fun record() {
            val ctm = graphicsState.currentTransformationMatrix
            val box = page.cropBox
            val w = abs(ctm.scalingFactorX.toDouble())
            val h = abs(ctm.scalingFactorY.toDouble())
            val x = (ctm.translateX - box.lowerLeftX) / box.width.toDouble()
            val yTop = 1 - ((ctm.translateY - box.lowerLeftY) + h) / box.height.toDouble()
            val rect = NormRect(x.coerceIn(0.0, 1.0), yTop.coerceIn(0.0, 1.0), (w / box.width).coerceIn(0.0, 1.0), (h / box.height).coerceIn(0.0, 1.0))
            val area = rect.w * rect.h
            if (area > 0.003 && area < 0.95) found += rect
        }
    }
}
