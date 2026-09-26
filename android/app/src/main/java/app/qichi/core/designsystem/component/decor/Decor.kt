package app.qichi.core.designsystem.component.decor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.qichi.core.designsystem.IbmPlexMono
import app.qichi.core.designsystem.NotoSansSc
import app.qichi.core.designsystem.NotoSerifSc
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.lift
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 装饰（docs/06-design-system.md §7）：尺寸、形状照设计稿生成器 tools/design/gen_screens.py 里的同名函数。
 * 全部跟随天色（颜色从 QichiTheme.colors 取），读屏软件跳过（clearAndSetSemantics），不拦点击。
 * 每屏最多三种；贴在内容边上，不压住要读的字。
 */

/** 装饰不朗读。 */
internal fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

/** 把 SVG 的 path 字符串变成 Compose 的 Path（坐标是设计稿 viewBox 里的）。 */
internal fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

/** 胶带：半透明长条，细竖纹，两端锯齿，斜贴。颜色默认雾蓝。 */
@Composable
fun Tape(modifier: Modifier = Modifier, color: Color = QichiTheme.colors.personB, width: Dp = 64.dp, rotation: Float = -5f, alpha: Float = 0.3f) {
    Canvas(modifier.size(width, 18.dp).rotate(rotation).decorative()) {
        val w = size.width
        val h = size.height
        val edge = Path().apply {
            moveTo(0f, 0f); lineTo(w, 0f); lineTo(w * .97f, h * .25f); lineTo(w, h * .5f); lineTo(w * .97f, h * .75f); lineTo(w, h)
            lineTo(0f, h); lineTo(w * .03f, h * .75f); lineTo(0f, h * .5f); lineTo(w * .03f, h * .25f); close()
        }
        clipPath(edge) {
            drawRect(color.copy(alpha = alpha))
            val stripe = 2.dp.toPx()
            var x = 0f
            while (x < w) {
                drawRect(Color.White.copy(alpha = .22f), Offset(x, 0f), Size(stripe, h))
                x += 6.dp.toPx()
            }
        }
    }
}

/**
 * 拍立得：card 色相框，下边留白写手写说明，斜放。说明是用户写的内容，会被朗读；相框不朗读。
 * [tape] 可以在相框上贴一条胶带（放在 BoxScope 里自己定位，比如 Modifier.align(Alignment.TopCenter)）。
 */
@Composable
fun Polaroid(
    modifier: Modifier = Modifier,
    caption: String? = null,
    rotation: Float = -2f,
    tape: (@Composable BoxScope.() -> Unit)? = null,
    photo: @Composable () -> Unit,
) {
    val colors = QichiTheme.colors
    Box(modifier.rotate(rotation)) {
        Column(
            // 相框只和照片一样宽（说明不把它撑宽）
            Modifier.width(IntrinsicSize.Min).lift(colors, RectangleShape).background(colors.card)
                .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = if (caption != null) 5.dp else 7.dp),
        ) {
            photo()
            if (caption != null) {
                Text(
                    caption,
                    style = QichiTheme.typography.hand.copy(fontSize = 17.sp, color = colors.muted, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
            }
        }
        tape?.invoke(this)
    }
}

/** 邮票：card 色底、四边一圈半圆齿孔（挖成页面底色），里面放一张图。 */
@Composable
fun Stamp(modifier: Modifier = Modifier, width: Dp, height: Dp, rotation: Float = 3f, content: @Composable () -> Unit) {
    val colors = QichiTheme.colors
    Box(
        modifier.size(width, height).rotate(rotation).drawWithContent {
            drawRect(colors.card)
            drawContent()
            val r = 2.6.dp.toPx()
            val step = 8.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                drawCircle(colors.background, r, Offset(x, 0f)); drawCircle(colors.background, r, Offset(x, size.height)); x += step
            }
            var y = step / 2
            while (y < size.height) {
                drawCircle(colors.background, r, Offset(0f, y)); drawCircle(colors.background, r, Offset(size.width, y)); y += step
            }
        }.padding(6.dp),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds()) { content() }
    }
}

/** 邮戳：圆圈里「栖迟」和日期，右边三道波浪线。 */
@Composable
fun Postmark(top: String, date: String, modifier: Modifier = Modifier, size: Dp = 66.dp, rotation: Float = -14f, color: Color = QichiTheme.colors.accent, waves: Boolean = true) {
    val c = color.copy(alpha = .75f)
    val inner = color.copy(alpha = .3f)
    Row(modifier.rotate(rotation).decorative(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size).drawBehind {
                drawCircle(c, style = Stroke(1.5.dp.toPx()), radius = this.size.minDimension / 2 - 0.75.dp.toPx())
                drawCircle(inner, style = Stroke(1.dp.toPx()), radius = this.size.minDimension / 2 - 4.dp.toPx())
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(top, style = TextStyle(fontFamily = NotoSansSc, fontWeight = FontWeight.W600, fontSize = 10.sp, letterSpacing = .2.em, color = c, lineHeight = 12.5.sp))
                Text(date, style = TextStyle(fontFamily = IbmPlexMono, fontSize = 9.sp, color = c, lineHeight = 11.sp))
            }
        }
        if (waves) {
            Canvas(Modifier.size(54.dp, 26.dp)) {
                val k = this.size.width / 54f
                scale(k, k, pivot = Offset.Zero) { wavePaths.forEach { drawPath(it, c, style = Stroke(1.3f)) } }
            }
        }
    }
}

private val wavePaths = listOf(5, 13, 21).map { y -> svgPath("M0 ${y}c4.5-4 9-4 13.5 0s9 4 13.5 0 9-4 13.5 0 9 4 13.5 0") }

/** 印章：暮玫瑰方章，宋体竖排（「栖迟」「定」），内圈白线。 */
@Composable
fun Seal(text: String = "栖迟", modifier: Modifier = Modifier, size: Dp = 38.dp, rotation: Float = -6f) {
    val colors = QichiTheme.colors
    Box(
        modifier.size(size).rotate(rotation).decorative()
            .background(colors.accent, RoundedCornerShape(4.dp))
            .drawBehind {
                val inset = 3.dp.toPx()
                drawRoundRect(
                    Color.White.copy(alpha = .55f), Offset(inset, inset), Size(this.size.width - inset * 2, this.size.height - inset * 2),
                    CornerRadius(2.dp.toPx()), style = Stroke(1.1.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = (size.value * .32f).sp
        // 竖排：两个字一列（上下），三个字以上分成两列，从右往左读
        val columns = if (text.length <= 2) listOf(text) else text.chunked((text.length + 1) / 2).reversed()
        Row(verticalAlignment = Alignment.CenterVertically) {
            columns.forEach { col ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    col.forEach { ch ->
                        Text(ch.toString(), style = TextStyle(fontFamily = NotoSerifSc, fontWeight = FontWeight.W600, fontSize = fontSize, lineHeight = fontSize * 1.05f, color = colors.paper))
                    }
                }
            }
        }
    }
}

/** 蜡封：不规则的圆，内圈线，中间宋体一个字。 */
@Composable
fun WaxSeal(char: String, modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = QichiTheme.colors.accent) {
    Box(modifier.size(size).decorative(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / 60f
            scale(k, k, pivot = Offset.Zero) {
                drawPath(waxOutline, color)
                drawPath(waxOutline, color, style = Stroke(3f, join = StrokeJoin.Round))
                drawCircle(Color.White.copy(alpha = .38f), 18f, Offset(30f, 30f), style = Stroke(1.4f))
                drawCircle(Color.White.copy(alpha = .12f), 9f, Offset(23f, 21f))
            }
        }
        Text(char, style = TextStyle(fontFamily = NotoSerifSc, fontWeight = FontWeight.W600, fontSize = (size.value * 17f / 60f).sp, color = Color.White.copy(alpha = .92f)))
    }
}

private val waxOutline: Path = Path().apply {
    for (i in 0 until 28) {
        val ang = PI * 2 * i / 28
        val r = 25.5 + (if (i % 2 == 0) 1.8 else -0.6) + (if (i % 7 == 0) 1.2 else 0.0)
        val x = (30 + r * cos(ang)).toFloat()
        val y = (30 + r * sin(ang)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** 贴纸：虚线圆角框 + 手写字，斜贴。字是给人看的（「需要安慰」），会被朗读。 */
@Composable
fun Sticker(text: String, modifier: Modifier = Modifier, color: Color = QichiTheme.colors.accent, rotation: Float = -6f, fontSizeSp: Float = 16f) {
    Box(
        modifier.rotate(rotation)
            .background(color.copy(alpha = .08f), RoundedCornerShape(14.dp))
            .drawBehind {
                drawRoundRect(
                    color.copy(alpha = .6f), cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(start = 10.dp, end = 10.dp, top = 1.dp, bottom = 3.dp),
    ) {
        Text(text, style = QichiTheme.typography.hand.copy(fontSize = fontSizeSp.sp, color = color), maxLines = 1)
    }
}

/** 书签带：暮玫瑰竖条，底部燕尾。放在纸张右上角（外面用 offset / align 定位）。 */
@Composable
fun Ribbon(modifier: Modifier = Modifier, color: Color = QichiTheme.colors.accent, height: Dp = 46.dp) {
    Canvas(modifier.size(16.dp, height).decorative()) {
        fun tail(dy: Float) = Path().apply {
            moveTo(0f, dy); lineTo(size.width, dy); lineTo(size.width, size.height + dy); lineTo(size.width / 2, size.height * .78f + dy); lineTo(0f, size.height + dy); close()
        }
        drawPath(tail(2.dp.toPx()), Color.Black.copy(alpha = .15f))
        drawPath(tail(0f), color)
    }
}

/** 荧光笔：字的下半截衬一道淡色（单行标题用；左右各多出 3dp）。 */
fun Modifier.marker(color: Color, alpha: Float = .24f): Modifier = drawBehind {
    val pad = 3.dp.toPx()
    drawRect(color.copy(alpha = alpha), Offset(-pad, size.height * .56f), Size(size.width + pad * 2, size.height * .34f))
}

/** 手写短句（龙藏体，微斜）：问候、功能名旁边那句、签名。是给人看的字，会被朗读。 */
@Composable
fun HandNote(text: String, modifier: Modifier = Modifier, fontSizeSp: Float = 20f, color: Color = QichiTheme.colors.muted, rotation: Float = -3f) {
    Text(text, style = QichiTheme.typography.hand.copy(fontSize = fontSizeSp.sp, color = color), modifier = modifier.rotate(rotation))
}

/** 水印数字：超大等宽数字，ink 5%（日历、时间线的月份）。放在内容后面。 */
@Composable
fun Watermark(text: String, modifier: Modifier = Modifier, fontSizeSp: Float = 130f, alpha: Float = .05f) {
    Text(
        text,
        style = TextStyle(fontFamily = IbmPlexMono, fontWeight = FontWeight.Light, fontSize = fontSizeSp.sp, lineHeight = fontSizeSp.sp, color = QichiTheme.colors.ink.copy(alpha = alpha)),
        modifier = modifier.decorative(),
    )
}

/**
 * 横线纸：paper 底、每 [lineHeight] 一道淡横线（从 [top] 开始），左边一条暮玫瑰竖线（[margin]）。
 * 写作编辑器、文稿缩略图用；行高和编辑器的固定行高一致，字就压在线上。
 */
@Composable
fun Modifier.ruledPaper(lineHeight: Dp, top: Dp = 0.dp, margin: Boolean = true, marginX: Dp = 40.dp): Modifier {
    val colors = QichiTheme.colors
    val line = colors.ink.copy(alpha = if (colors.isDark) .12f else .09f)
    val marginColor = colors.accent.copy(alpha = .4f)
    return this.background(colors.paper).drawBehind {
        val lh = lineHeight.toPx()
        var y = top.toPx() + lh - 1.dp.toPx()
        while (y < size.height) {
            drawRect(line, Offset(0f, y), Size(size.width, 1.dp.toPx()))
            y += lh
        }
        if (margin) drawRect(marginColor, Offset(marginX.toPx(), 0f), Size(1.dp.toPx(), size.height))
    }
}

/** 在 viewBox 坐标里画：把 [vw]×[vh] 的设计稿坐标铺满画布（slice：短边对齐，多出的裁掉）。 */
internal inline fun androidx.compose.ui.graphics.drawscope.DrawScope.inViewBox(vw: Float, vh: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val k = maxOf(size.width / vw, size.height / vh)
    val dx = (size.width - vw * k) / 2
    val dy = (size.height - vh * k) / 2
    translate(dx, dy) { scale(k, k, pivot = Offset.Zero) { block() } }
}
