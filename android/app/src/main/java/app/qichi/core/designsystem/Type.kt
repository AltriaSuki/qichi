package app.qichi.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字号层级（docs/06-design-system.md §3「晨雾 · 新方向」）。行高是字号的倍数，字距单位 em。
 * 颜色不写在这里，由使用处从 QichiTheme.colors 取。
 *
 * 上一版的几个名字（hubTitle、pageTitle、feeling、question、tocItem）还在，指向新的层级，
 * 各页换成新界面时（P10-03 起）逐步改用新名字。
 */
@Immutable
data class QichiTypography(
    /** 今天页的日期大字「24」：Mono 300 96。大字模式不放大 */
    val dateDisplay: TextStyle,
    /** 主页大标题「一起」（30）；功能首页用 [featureTitle]（28）。下面衬荧光笔 */
    val largeTitle: TextStyle,
    val featureTitle: TextStyle,
    /** 单项页顶栏标题「秋天去一次海边」 */
    val barTitle: TextStyle,
    /** 单项页顶栏上的功能名（用功能色） */
    val barFeature: TextStyle,
    /** 列表标题、问答题目、心情词（17–22，按场合 copy 字号） */
    val headline: TextStyle,
    /** 正文、列表（15） */
    val body: TextStyle,
    /** 稍大的正文（16） */
    val bodyLarge: TextStyle,
    /** 列表里两行摘要（muted） */
    val preview: TextStyle,
    /** 「心情」「待办」小标题（muted） */
    val sectionLabel: TextStyle,
    /** 辅助信息（muted） */
    val caption: TextStyle,
    /** 数字、日期、时间、版本号：Mono。字号按场合用 copy(fontSize = …) 调整 */
    val numeral: TextStyle,
    /** 标签 `#旅行/北方` */
    val tag: TextStyle,
    /** 手写短句「午后好」「需要安慰」：龙藏体。大字模式不放大 */
    val hand: TextStyle,
    /** 书页：思源宋体 */
    val reading: TextStyle,
    /** 写作编辑器：行高固定对齐横线纸 */
    val editor: TextStyle,
    /** 按钮 */
    val button: TextStyle,
    /** 底部标签的字（选中时字重 600） */
    val tab: TextStyle,
    /** 「大字」模式下的放大倍数；标准模式为 1 */
    val scale: Float = 1f,
) {
    // ── 上一版的名字 ──
    /** = [largeTitle] */
    val hubTitle: TextStyle get() = largeTitle
    /** = [barTitle] */
    val pageTitle: TextStyle get() = barTitle
    /** 心情词 = [headline] 22 */
    val feeling: TextStyle get() = headline.copy(fontSize = headline.fontSize * (22f / 17f), lineHeight = headline.lineHeight * (22f / 17f))
    /** 问答题目 = [headline] 20 */
    val question: TextStyle get() = headline.copy(fontSize = headline.fontSize * (20f / 17f), lineHeight = headline.lineHeight * (20f / 17f))
    /** 目录条目 = [headline] */
    val tocItem: TextStyle get() = headline

    /** 「大字」模式：全部 ×[factor]，dateDisplay 和手写字不变；编辑器的固定行高跟着放大。 */
    fun scaled(factor: Float): QichiTypography {
        if (factor == 1f) return this
        fun TextStyle.x() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
        return copy(
            scale = factor,
            largeTitle = largeTitle.x(), featureTitle = featureTitle.x(), barTitle = barTitle.x(), barFeature = barFeature.x(),
            headline = headline.x(), body = body.x(), bodyLarge = bodyLarge.x(), preview = preview.x(),
            sectionLabel = sectionLabel.x(), caption = caption.x(), numeral = numeral.x(), tag = tag.x(),
            reading = reading.x(), editor = editor.x(), button = button.x(), tab = tab.x(),
        )
    }

    companion object {
        const val LARGE_TEXT_FACTOR = 1.2f

        /** 编辑器的固定行高（对齐横线纸），标准字号下 */
        const val EDITOR_LINE_SP = 33f
    }
}

/**
 * 页面里单独指定的字号写 `28.tsp`（不写 `28.sp`），这样会跟着「大字」一起放大。
 * 只有 dateDisplay、手写字和人物圆标里的字（跟着圆的大小走）不用它。
 */
val Number.tsp: TextUnit
    @Composable @ReadOnlyComposable
    get() = (toFloat() * LocalQichiTypography.current.scale).sp

private val TrimNone = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(family: FontFamily, weight: Int, size: Float, lineHeight: Float, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = letterSpacing.em,
    lineHeightStyle = TrimNone,
)

val DefaultTypography = QichiTypography(
    dateDisplay = style(IbmPlexMono, 300, 96f, 0.76f, -0.05f),
    largeTitle = style(NotoSansSc, 600, 30f, 1.3f, 0.02f),
    featureTitle = style(NotoSansSc, 600, 28f, 1.3f, 0.02f),
    barTitle = style(NotoSansSc, 600, 17f, 1.45f),
    barFeature = style(NotoSansSc, 700, 12f, 1.4f, 0.04f),
    headline = style(NotoSansSc, 700, 17f, 1.5f),
    body = style(NotoSansSc, 400, 15f, 1.7f),
    bodyLarge = style(NotoSansSc, 400, 16f, 1.7f),
    preview = style(NotoSansSc, 400, 14f, 1.6f),
    sectionLabel = style(NotoSansSc, 700, 13f, 1.5f, 0.04f),
    caption = style(NotoSansSc, 400, 13f, 1.5f),
    numeral = style(IbmPlexMono, 400, 17f, 1f),
    tag = style(IbmPlexMono, 500, 12.5f, 1.75f),
    hand = style(LongCang, 400, 22f, 1.2f),
    reading = style(NotoSerifSc, 400, 18f, 2.0f, 0.03f),
    editor = style(NotoSansSc, 400, 16f, QichiTypography.EDITOR_LINE_SP / 16f),
    button = style(NotoSansSc, 500, 15f, 1f, 0.04f),
    tab = style(NotoSansSc, 500, 12f, 1f, 0.06f),
)
