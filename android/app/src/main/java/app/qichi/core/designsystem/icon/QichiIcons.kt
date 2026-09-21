package app.qichi.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 设计稿里的细线图标（24×24 画布、1.2 线宽、圆头圆角）。
 * 用 `Icon(QichiIcons.Back, contentDescription, tint = …)` 显示，颜色由 tint 决定。
 */
object QichiIcons {
    val Back: ImageVector by lazy { stroke("back", "M15 5l-7 7 7 7") }
    val Forward: ImageVector by lazy { stroke("forward", "M9 5l7 7-7 7") }
    val Search: ImageVector by lazy { stroke("search", "M5 11a6 6 0 1 0 12 0a6 6 0 1 0 -12 0M20 20l-4.5-4.5") }
    val Plus: ImageVector by lazy { stroke("plus", "M12 5v14M5 12h14") }
    val Send: ImageVector by lazy { stroke("send", "M12 19V5M6 11l6-6 6 6", width = 1.5f) }
    val Down: ImageVector by lazy { stroke("down", "M12 5v14M6 13l6 6 6-6", width = 1.5f) }
    val Check: ImageVector by lazy { stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 1.8f) }
    val Clock: ImageVector by lazy { stroke("clock", "M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0M12 8v4l3 2") }
    val File: ImageVector by lazy { stroke("file", "M7 3h7l5 5v13H7zM14 3v5h5", width = 1.1f) }
    val Repeat: ImageVector by lazy {
        stroke("repeat", "M17 4l3 3-3 3M20 7H8a4 4 0 0 0-4 4M7 20l-3-3 3-3M4 17h12a4 4 0 0 0 4-4")
    }
    val Close: ImageVector by lazy { stroke("close", "M6 6l12 12M18 6L6 18") }
    val More: ImageVector by lazy {
        stroke("more", "M5 12h.01M12 12h.01M19 12h.01", width = 2.2f)
    }

    private fun stroke(name: String, pathData: String, width: Float = 1.2f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
