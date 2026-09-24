package app.qichi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.qichi.shared.rules.DocumentImages
import java.util.UUID
import app.qichi.core.designsystem.QichiShapes
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.qichi.core.designsystem.QichiTheme

/**
 * 够用的 Markdown：标题（# ～ ######）、列表（- * 1.）、引用（>）、分隔线（---）、段落，
 * 行内的 **粗体**、*斜体*、`代码`。共同写作的预览、编辑器里的淡色标记、大纲都用它。
 */
object Markdown {
    sealed interface Block {
        /** [line] 是这一块在原文里的第一行（从 0 开始），大纲跳转用 */
        val line: Int

        data class Heading(val level: Int, val text: String, override val line: Int) : Block
        data class Paragraph(val text: String, override val line: Int) : Block
        data class Item(val marker: String, val text: String, override val line: Int) : Block

        /** 照片：单独一行 ![说明](qichi-file:文件id)（P9-02） */
        data class Image(val fileId: UUID, val alt: String, override val line: Int) : Block

        /** 勾选框「- [ ] 」「- [x] 」 */
        data class Task(val checked: Boolean, val text: String, override val line: Int) : Block
        data class Quote(val text: String, override val line: Int) : Block
        data class Rule(override val line: Int) : Block
    }

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*([-*+])\\s+(.*)$")
    private val task = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val ordered = Regex("^\\s*(\\d{1,3}[.)])\\s+(.*)$")
    private val quote = Regex("^>\\s?(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(text: String): List<Block> {
        val lines = text.lines()
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var paragraphStart = 0
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.toString(), paragraphStart)
            paragraph.clear()
        }
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); blocks += Block.Rule(i) }
                heading.matches(line) -> { flush(); heading.find(line)!!.let { blocks += Block.Heading(it.groupValues[1].length, it.groupValues[2].trim(), i) } }
                DocumentImages.line.matches(line) -> {
                    flush()
                    val m = DocumentImages.line.find(line)!!
                    blocks += Block.Image(UUID.fromString(m.groupValues[2]), m.groupValues[1], i)
                }
                task.matches(line) -> { flush(); task.find(line)!!.let { blocks += Block.Task(it.groupValues[1] != " ", it.groupValues[2], i) } }
                bullet.matches(line) -> { flush(); bullet.find(line)!!.let { blocks += Block.Item("·", it.groupValues[2], i) } }
                ordered.matches(line) -> { flush(); ordered.find(line)!!.let { blocks += Block.Item(it.groupValues[1], it.groupValues[2], i) } }
                quote.matches(line) -> {
                    flush()
                    val body = quote.find(line)!!.groupValues[1]
                    val last = blocks.lastOrNull()
                    // 连续的引用行并成一块
                    if (last is Block.Quote && last.line + last.text.count { it == '\n' } + 1 == i) {
                        blocks[blocks.lastIndex] = last.copy(text = last.text + "\n" + body)
                    } else {
                        blocks += Block.Quote(body, i)
                    }
                }
                else -> {
                    if (paragraph.isEmpty()) paragraphStart = i else paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** 大纲：所有标题。 */
    fun headings(text: String): List<Block.Heading> = parse(text).filterIsInstance<Block.Heading>()

    private val inlinePattern = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")

    /** 行内样式：粗体、斜体、代码；标记符号本身去掉。 */
    fun inline(text: String, codeColor: Color): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in inlinePattern.findAll(text)) {
            append(text.substring(last, m.range.first))
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.W500)) { append(m.groupValues[1]) }
                m.groups[2] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
                else -> withStyle(SpanStyle(color = codeColor, letterSpacing = 0.em)) { append(m.groupValues[3]) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

    /**
     * 编辑器里的原文着色：不改动任何字符（光标位置不受影响），只把标题行放大、
     * 把 `## `、`- `、`> ` 这样的标记淡化。
     */
    fun highlight(text: String, markerColor: Color, headingSize: TextUnit): AnnotatedString = buildAnnotatedString {
        append(text)
        var offset = 0
        for (line in text.split('\n')) {
            val markerEnd = when {
                heading.matches(line) -> {
                    addStyle(SpanStyle(fontSize = headingSize, letterSpacing = 0.12.em), offset, offset + line.length)
                    line.indexOfFirst { it != '#' }.let { if (it < 0) line.length else it + 1 }.coerceAtMost(line.length)
                }
                DocumentImages.line.matches(line) -> line.length
                task.matches(line) -> task.find(line)!!.groups[2]!!.range.first
                bullet.matches(line) || ordered.matches(line) ->
                    (bullet.find(line) ?: ordered.find(line))!!.groups[2]!!.range.first
                quote.matches(line) -> if (line.startsWith("> ")) 2 else 1
                rule.matches(line) -> line.length
                else -> 0
            }
            if (markerEnd > 0) addStyle(SpanStyle(color = markerColor), offset, offset + markerEnd)
            offset += line.length + 1
        }
    }
}

/**
 * Markdown 预览。字号、行距跟随编辑器的本机设置。
 * [onToggleTask] 不为空时勾选框可以点（参数是那一行在原文里的行号）。
 * [image] 画一张照片（文稿里用）；为空时照片只显示成「（照片）」这样的一行字。
 */
@Composable
fun MarkdownView(
    text: String,
    fontSize: TextUnit,
    lineHeight: Float,
    modifier: Modifier = Modifier,
    onToggleTask: ((Int) -> Unit)? = null,
    image: (@Composable (fileId: UUID, alt: String) -> Unit)? = null,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val body = type.body.copy(fontSize = fontSize, lineHeight = fontSize * lineHeight, fontWeight = FontWeight.W300, letterSpacing = 0.03.em, color = colors.ink)
    Column(modifier) {
        Markdown.parse(text).forEach { block ->
            when (block) {
                is Markdown.Block.Heading -> Text(
                    Markdown.inline(block.text, colors.muted),
                    style = body.copy(fontSize = fontSize * (if (block.level <= 2) 1.3f else 1.12f), lineHeight = fontSize * 1.3f * 1.6f, letterSpacing = 0.12.em),
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp).semantics { heading() },
                )
                is Markdown.Block.Paragraph -> Text(Markdown.inline(block.text, colors.muted), style = body, modifier = Modifier.padding(bottom = 14.dp))
                is Markdown.Block.Item -> Row(Modifier.fillMaxWidth()) {
                    Text(block.marker, style = body.copy(color = colors.faint), modifier = Modifier.widthIn(min = 22.dp))
                    Text(Markdown.inline(block.text, colors.muted), style = body, modifier = Modifier.weight(1f))
                }
                is Markdown.Block.Image -> Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
                    if (image != null) {
                        image(block.fileId, block.alt)
                    } else {
                        Text("（${block.alt.ifBlank { "照片" }}）", style = body.copy(color = colors.muted))
                    }
                }
                is Markdown.Block.Task -> Row(
                    Modifier.fillMaxWidth().then(
                        if (onToggleTask != null) {
                            Modifier.toggleable(value = block.checked, role = Role.Checkbox, onValueChange = { onToggleTask(block.line) })
                        } else {
                            Modifier.semantics { stateDescription = if (block.checked) "已完成" else "未完成" }
                        },
                    ),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.padding(top = (fontSize.value * (lineHeight - 1f) / 2f + 2f).dp, end = 10.dp).size((fontSize.value * 0.95f).dp)
                            .border(1.dp, if (block.checked) colors.accent else colors.line2, QichiShapes.card)
                            .background(if (block.checked) colors.accent else colors.paper, QichiShapes.card),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (block.checked) Text("✓", style = body.copy(fontSize = fontSize * 0.7f, lineHeight = fontSize * 0.8f, color = colors.paper))
                    }
                    Text(
                        Markdown.inline(block.text, colors.muted),
                        style = if (block.checked) body.copy(color = colors.muted, textDecoration = TextDecoration.LineThrough) else body,
                        modifier = Modifier.weight(1f),
                    )
                }
                is Markdown.Block.Quote -> Row(Modifier.padding(vertical = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.line2))
                    Text(Markdown.inline(block.text, colors.muted), style = body.copy(color = colors.muted), modifier = Modifier.padding(start = 14.dp))
                }
                is Markdown.Block.Rule -> Box(Modifier.padding(vertical = 18.dp).fillMaxWidth().height(1.dp).background(colors.line))
            }
        }
    }
}
