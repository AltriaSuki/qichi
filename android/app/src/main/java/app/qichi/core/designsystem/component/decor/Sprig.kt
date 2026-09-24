package app.qichi.core.designsystem.component.decor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme

private val stem = svgPath("M4 8C30 4 44 26 70 22S104 6 116 16")
private val leaf = svgPath("M0 -1C-3 1-9 0-9-6C-9-12-4-15 0-19C4-15 9-12 9-6C9 0 3 1 0-1Z")
private val vein = svgPath("M0-1V-15")

/** 七片叶子：位置、角度、大小（设计稿 viewBox 里） */
private val leaves = listOf(
    floatArrayOf(16f, 7f, -70f, .8f), floatArrayOf(32f, 12f, 110f, .9f), floatArrayOf(50f, 21f, -40f, 1f), floatArrayOf(68f, 21f, 130f, .85f),
    floatArrayOf(86f, 14f, -60f, .95f), floatArrayOf(102f, 10f, 120f, .8f), floatArrayOf(116f, 15f, -20f, .7f),
)

/**
 * 绿萝线描：一根弯茎、七片心形叶，雾蓝线、淡填充（主页右上角、聊天背景角落、空页、加载中）。
 * 高度是宽度的 0.4；[flip] 左右翻转。
 */
@Composable
fun Sprig(modifier: Modifier = Modifier, width: Dp = 120.dp, flip: Boolean = false, color: Color = QichiTheme.colors.personB, alpha: Float = 1f) {
    val fill = color.copy(alpha = .16f * alpha)
    val line = color.copy(alpha = .7f * alpha)
    Canvas(modifier.size(width, width * .4f).decorative()) {
        // viewBox 0 -14 120 48
        val k = size.width / 120f
        scale(k, k, pivot = Offset.Zero) {
            translate(0f, 14f) {
                withTransform({ if (flip) { translate(120f, 0f); scale(-1f, 1f, pivot = Offset.Zero) } }) {
                    drawPath(stem, line, style = Stroke(1.2f, cap = StrokeCap.Round))
                    leaves.forEach { (x, y, a, s) ->
                        translate(x, y) {
                            rotate(a, pivot = Offset.Zero) {
                                scale(s, s, pivot = Offset.Zero) {
                                    drawPath(leaf, fill)
                                    drawPath(leaf, line, style = Stroke(1f))
                                    drawPath(vein, line, style = Stroke(.8f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
