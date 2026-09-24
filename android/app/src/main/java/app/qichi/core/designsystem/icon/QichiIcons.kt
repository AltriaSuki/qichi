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
    val ChevronLeft: ImageVector by lazy { stroke("chevron-left", "M15 5l-7 7 7 7") }
    val ChevronRight: ImageVector by lazy { stroke("chevron-right", "M9 5l7 7-7 7") }
    /** 预览（眼睛） */
    val Eye: ImageVector by lazy {
        stroke("eye", "M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6zM9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0")
    }
    /** 编辑（笔） */
    val Pen: ImageVector by lazy { stroke("pen", "M4 20l1-4L16 5l3 3L8 19zM14 7l3 3") }
    /** 专注（四角） */
    val Focus: ImageVector by lazy { stroke("focus", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5") }
    /** 字号与行距 */
    val TextSize: ImageVector by lazy { stroke("text-size", "M3 19l5-13 5 13M5 14.5h6M14 19l3.5-8 3.5 8M15.2 16.5h4.6") }
    /** 书签 */
    val Bookmark: ImageVector by lazy { stroke("bookmark", "M7 4h10v16l-5-4-5 4z") }
    /** 大纲 */
    val Outline: ImageVector by lazy { stroke("outline", "M4 6h16M8 12h12M8 18h12M4 12h.01M4 18h.01") }

    // ── 写作格式按钮（P9-01） ──
    val Heading: ImageVector by lazy { stroke("heading", "M6 5v14M17 5v14M6 12h11", width = 1.4f) }
    val Bold: ImageVector by lazy { stroke("bold", "M7 5h5.5a3.5 3.5 0 0 1 0 7H7zM7 12h6.5a3.5 3.5 0 0 1 0 7H7z", width = 1.6f) }
    val BulletList: ImageVector by lazy { stroke("bullet-list", "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01", width = 1.4f) }
    val TaskList: ImageVector by lazy { stroke("task-list", "M4 5h5v5H4zM5.2 7.6l1.2 1.2 2-2.2M13 7.5h7M4 14h5v5H4zM13 16.5h7") }
    val Quote: ImageVector by lazy { stroke("quote", "M5 5v14M10 8h9M10 12h9M10 16h6", width = 1.3f) }
    val Rule: ImageVector by lazy { stroke("rule", "M4 12h16M8 7h8M8 17h8") }
    val Undo: ImageVector by lazy { stroke("undo", "M9 13L4 8l5-5M4 8h10a6 6 0 0 1 0 12h-3") }
    val Redo: ImageVector by lazy { stroke("redo", "M15 13l5-5-5-5M20 8H10a6 6 0 0 0 0 12h3") }
    val Comment: ImageVector by lazy { stroke("comment", "M5 5h14v10H10l-4 4v-4H5zM8.5 9h7M8.5 12h4") }
    val Image: ImageVector by lazy { stroke("image", "M4 5h16v14H4zM4 16l5-5 4 4 2.5-2.5L20 17M15.5 9h.01") }

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
