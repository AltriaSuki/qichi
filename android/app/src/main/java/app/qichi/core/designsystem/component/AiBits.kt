package app.qichi.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp

/** AI 标记：四角星 +「AI」，雾蓝色。 */
@Composable
fun AiMark(modifier: Modifier = Modifier, size: Int = 13) {
    val color = QichiTheme.colors.personB
    Row(
        modifier.clearAndSetSemantics { contentDescription = "AI" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(QichiIcons.Spark, contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
        Text("AI", style = QichiTheme.typography.numeral.copy(fontSize = size.tsp, fontWeight = FontWeight.W500, color = color))
    }
}

/** 依据编号：雾蓝淡底的小圆角标签（AI 回答、总结里的 [n]）。可点时由外层加 clickable。 */
@Composable
fun RefChip(number: Int, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 19.dp)
            .background(colors.personB.copy(alpha = .14f), QichiShapes.pill)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = QichiTheme.typography.numeral.copy(fontSize = 11.tsp, fontWeight = FontWeight.W500, color = colors.personB),
        )
    }
}

/** AI 正在写：三个点依次变淡；「减少动画」时静止。 */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, color: Color = QichiTheme.colors.personB) {
    val reduce = QichiTheme.reduceMotion
    val phase = if (reduce) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "thinking")
        val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "phase")
        p
    }
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(.9f, .6f, .3f).forEachIndexed { i, base ->
            // 最亮的那一点依次往后移
            val a = if (reduce) base else listOf(.9f, .6f, .3f)[((i - phase.toInt()) % 3 + 3) % 3]
            Box(Modifier.size(6.dp).alpha(a).background(color, QichiShapes.pill))
        }
    }
}

/** 虚线边框（AI 提议卡片、贴纸）：线宽 [width]，圆角 [radius]。 */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.3.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** 心情强度：十个小圆点，前 [value] 个是实心功能色，其余是淡色。 */
@Composable
fun IntensityDots(value: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "强度 $value" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 10) {
            Box(Modifier.size(7.dp).background(if (i < value) color else color.copy(alpha = .18f), QichiShapes.pill))
        }
    }
}
