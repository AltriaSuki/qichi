package app.qichi.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字号层级（docs/06-design-system.md §3）。行高是字号的倍数，字距单位 em。
 * 颜色不写在这里，由使用处从 QichiTheme.colors 取。
 */
@Immutable
data class QichiTypography(
    /** 今天页的日期大字「21」：Cormorant 300 */
    val dateDisplay: TextStyle,
    /** 「一起」这样的大标题 */
    val hubTitle: TextStyle,
    /** 返回条标题、「九月」 */
    val pageTitle: TextStyle,
    /** 心情词「有点累」 */
    val feeling: TextStyle,
    /** 问答题目 */
    val question: TextStyle,
    /** 「一起」目录条目 */
    val tocItem: TextStyle,
    /** 正文、列表 */
    val body: TextStyle,
    /** 稍大的正文（列表主行 16sp） */
    val bodyLarge: TextStyle,
    /** 书页、文稿 */
    val reading: TextStyle,
    /** 「心情」「待办」小标题 */
    val sectionLabel: TextStyle,
    /** 辅助信息 */
    val caption: TextStyle,
    /** 数字、时间、版本号、罗马数字：Cormorant 斜体。字号按场合用 copy(fontSize = …) 调整 */
    val numeral: TextStyle,
    /** 底部标签（未选中；选中时字重改为 400） */
    val tab: TextStyle,
    /** 「大字」模式下的放大倍数；标准模式为 1 */
    val scale: Float = 1f,
) {
    /** 「大字」模式：全部 ×[factor]，dateDisplay 不变。 */
    fun scaled(factor: Float): QichiTypography {
        if (factor == 1f) return this
        fun TextStyle.x() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
        return copy(
            scale = factor,
            hubTitle = hubTitle.x(), pageTitle = pageTitle.x(), feeling = feeling.x(), question = question.x(),
            tocItem = tocItem.x(), body = body.x(), bodyLarge = bodyLarge.x(), reading = reading.x(),
            sectionLabel = sectionLabel.x(), caption = caption.x(), numeral = numeral.x(), tab = tab.x(),
        )
    }

    companion object {
        const val LARGE_TEXT_FACTOR = 1.2f
    }
}

/**
 * 页面里单独指定的字号写 `28.tsp`（不写 `28.sp`），这样会跟着「大字」一起放大。
 * 只有 dateDisplay 和人物圆标里的字（跟着圆的大小走）不用它。
 */
val Number.tsp: TextUnit
    @Composable @ReadOnlyComposable
    get() = (toFloat() * LocalQichiTypography.current.scale).sp

private val TrimNone = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    family: FontFamily,
    weight: Int,
    size: Float,
    lineHeight: Float,
    letterSpacing: Float,
    italic: Boolean = false,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = letterSpacing.em,
    lineHeightStyle = TrimNone,
)

val DefaultTypography = QichiTypography(
    dateDisplay = style(CormorantGaramond, 300, 156f, 0.72f, -0.02f),
    hubTitle = style(NotoSerifSc, 200, 46f, 1.2f, 0.32f),
    pageTitle = style(NotoSerifSc, 300, 19f, 1.5f, 0.2f),
    feeling = style(NotoSerifSc, 200, 26f, 1.3f, 0.08f),
    question = style(NotoSerifSc, 200, 22f, 1.8f, 0.04f),
    tocItem = style(NotoSerifSc, 200, 23f, 1.4f, 0.2f),
    body = style(NotoSerifSc, 300, 15f, 1.8f, 0.03f),
    bodyLarge = style(NotoSerifSc, 300, 16f, 1.8f, 0.03f),
    reading = style(NotoSerifSc, 300, 17f, 2.08f, 0.03f),
    sectionLabel = style(NotoSerifSc, 400, 12f, 1.6f, 0.34f),
    caption = style(NotoSerifSc, 300, 13f, 1.5f, 0.1f),
    numeral = style(CormorantGaramond, 400, 17f, 1f, 0f, italic = true),
    tab = style(NotoSerifSc, 300, 15f, 1f, 0.3f),
)
