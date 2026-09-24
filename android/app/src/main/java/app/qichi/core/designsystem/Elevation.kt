package app.qichi.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 浮起阴影 `lift`（docs/06-design-system.md §4）：底边一条 1dp 的线，加一层很淡的大阴影。
 * 白天 `0 1 0 line, 0 12 28 rgba(43,50,58,.08)`；深夜两层都是黑色 .3。新建按钮用 [heavy]。
 */
fun Modifier.lift(colors: QichiColors, shape: Shape = QichiShapes.card, heavy: Boolean = false): Modifier {
    val shadowColor = if (colors.isDark) Color.Black.copy(alpha = 0.3f) else Color(0xFF2B323A).copy(alpha = if (heavy) 0.16f else 0.08f)
    val edge = if (colors.isDark) Color.Black.copy(alpha = 0.3f) else colors.line
    return this
        .drawBehind {
            // 底边那条线：比卡片低 1dp
            val y = size.height + 0.5.dp.toPx()
            drawLine(edge, Offset(size.width * 0.04f, y), Offset(size.width * 0.96f, y), strokeWidth = 1.dp.toPx())
        }
        .shadow(elevation = if (heavy) 16.dp else 10.dp, shape = shape, ambientColor = shadowColor, spotColor = shadowColor, clip = false)
}

/** 1dp 虚线分隔（line2）：分隔线不用实线。 */
fun Modifier.dashedDivider(colors: QichiColors, atTop: Boolean = false): Modifier = drawBehind {
    val y = if (atTop) 0.5.dp.toPx() else size.height - 0.5.dp.toPx()
    drawLine(
        colors.line2, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    )
}
