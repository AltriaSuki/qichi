package app.qichi.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 设计稿里的细线图标（24×24 画布、1.5 线宽、圆头圆角），来自 `tools/design/gen_screens.py` 的 `IC`。
 * 用 `Icon(QichiIcons.Back, contentDescription, tint = …)` 显示，颜色由 tint 决定。
 */
object QichiIcons {
    // ── 生成区开始（tools/design/gen_icons.py，不要手改） ──
    val Back: ImageVector by lazy { icon("back", "M15 5l-7 7 7 7") }
    val Search: ImageVector by lazy { icon("search", "M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0M20 20l-4.3-4.3") }
    val More: ImageVector by lazy { icon("more", "M5.5 12h.01M12 12h.01M18.5 12h.01" to 2.6f) }
    val Plus: ImageVector by lazy { icon("plus", "M12 5v14M5 12h14") }
    val Check: ImageVector by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    val ChevronRight: ImageVector by lazy { icon("chev", "M9 5l7 7-7 7") }
    val ChevronLeft: ImageVector by lazy { icon("chevl", "M15 5l-7 7 7 7") }
    val Calendar: ImageVector by lazy { icon("cal", "M6 5.5h12a2 2 0 0 1 2 2v10.5a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-10.5a2 2 0 0 1 2 -2zM4 10h16M8.5 3.5v4M15.5 3.5v4") }
    val CalendarToday: ImageVector by lazy { icon("today", "M6 5.5h12a2 2 0 0 1 2 2v10.5a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-10.5a2 2 0 0 1 2 -2zM4 10h16M8.5 3.5v4M15.5 3.5v4", "M12 15h.01" to 2.8f) }
    val Eye: ImageVector by lazy { icon("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12zM9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0") }
    val Focus: ImageVector by lazy { icon("focus", "M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4") }
    val People: ImageVector by lazy { icon("people", "M6 8.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M3.5 19.5c.8-3 3-4.6 5.5-4.6s4.7 1.6 5.5 4.6M14.1 9.5a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0M15.6 14.7c2.2-.3 4.2 1 4.9 3.6") }
    val Mood: ImageVector by lazy { icon("mood", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0M8.6 14.2c1.9 2 4.9 2 6.8 0", "M9 10h.01M15 10h.01" to 2.4f) }
    val Qna: ImageVector by lazy { icon("qna", "M4.5 5.5h15v10.5h-8.5l-4.5 3.5V16H4.5zM10.2 9a1.9 1.9 0 1 1 2.7 1.7c-.6.3-.9.7-.9 1.3", "M12 13.9h.01" to 2.4f) }
    val Flag: ImageVector by lazy { icon("flag", "M6 20.5V4.5M6 5h11l-2.5 4 2.5 4H6") }
    val Todo: ImageVector by lazy { icon("todo", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0M8.5 12.2l2.4 2.4 4.6-4.9") }
    val Idea: ImageVector by lazy { icon("idea", "M9.5 17.5h5M10.3 20.5h3.4M12 3.5a5.5 5.5 0 0 0-3.3 9.9c.6.5.8 1 .8 1.6v.5h5V15c0-.6.2-1.1.8-1.6A5.5 5.5 0 0 0 12 3.5z") }
    val Pen: ImageVector by lazy { icon("pen", "M4.5 19.5l1-4.4L15.6 5a2.1 2.1 0 0 1 3 3L8.5 18.5zM13.8 6.8l3 3") }
    val Mail: ImageVector by lazy { icon("mail", "M5.5 6h13a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2zM4.5 7.5l7.5 5.5 7.5-5.5") }
    val Archive: ImageVector by lazy { icon("archive", "M4.5 4.5h15a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-15a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1zM5 8.5v10.5h14V8.5M10 12.5h4") }
    val Sign: ImageVector by lazy { icon("sign", "M12 3v18M12 5h6l2 2.5-2 2.5h-6M12 12.5H6L4 15l2 2.5h6") }
    val Timeline: ImageVector by lazy { icon("timeline", "M7 4v16M11 7h8M11 12h8M11 17h5M5.4 7a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0M5.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0M5.4 17a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0") }
    val Book: ImageVector by lazy { icon("book", "M12 6.5c-2-1.5-4.5-2-8-2v13c3.5 0 6 .5 8 2 2-1.5 4.5-2 8-2v-13c-3.5 0-6 .5-8 2zM12 6.5v13") }
    val Review: ImageVector by lazy { icon("review", "M6 3.5h8l4 4v13H6zM14 3.5v4h4M9 13.5l2 2 4-4") }
    val Summary: ImageVector by lazy { icon("summary", "M5 6h14M5 10h14M5 14h9M5 18h6") }
    val Lock: ImageVector by lazy { icon("lock", "M7.5 10.5h9a2 2 0 0 1 2 2v5.5a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-5.5a2 2 0 0 1 2 -2zM8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5") }
    val Send: ImageVector by lazy { icon("send", "M12 19V5M6 11l6-6 6 6") }
    val Clock: ImageVector by lazy { icon("clock", "M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0M12 8v4l3 2") }
    val Toc: ImageVector by lazy { icon("toc", "M5 7h14M5 12h14M5 17h9") }
    val Bookmark: ImageVector by lazy { icon("bookmark", "M7 4h10v16l-5-4-5 4z") }
    val Undo: ImageVector by lazy { icon("undo", "M9 8l-4 4 4 4M5 12h9.5a4.5 4.5 0 0 1 0 9H12") }
    val Redo: ImageVector by lazy { icon("redo", "M15 8l4 4-4 4M19 12H9.5a4.5 4.5 0 0 0 0 9H12") }
    val Image: ImageVector by lazy { icon("photo", "M5.5 5.5h13a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2zM7.5 10.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M20.5 16l-5-5-8.5 8") }
    val Quote: ImageVector by lazy { icon("quote", "M9.5 7H5.5v5h4v1.2c0 1.8-1 3-2.8 3.8M18.5 7h-4v5h4v1.2c0 1.8-1 3-2.8 3.8") }
    val BulletList: ImageVector by lazy { icon("list", "M9.5 7h10M9.5 12h10M9.5 17h10", "M5 7h.01M5 12h.01M5 17h.01" to 2.6f) }
    val Checkbox: ImageVector by lazy { icon("checkbox", "M7.5 4.5h9a3 3 0 0 1 3 3v9a3 3 0 0 1 -3 3h-9a3 3 0 0 1 -3 -3v-9a3 3 0 0 1 3 -3zM8.5 12.2l2.4 2.4 4.6-4.9") }
    val Repeat: ImageVector by lazy { icon("repeat", "M17 4l3 3-3 3M20 7H8a4 4 0 0 0-4 4M7 20l-3-3 3-3M4 17h12a4 4 0 0 0 4-4") }
    val Pin: ImageVector by lazy { icon("pin", "M9 4h6l-1 5 3 3H7l3-3zM12 12v8") }
    val Sun: ImageVector by lazy { icon("sun", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M12 2.8v2.2M12 19v2.2M2.8 12h2.2M19 12h2.2M5.5 5.5l1.5 1.5M17 17l1.5 1.5M5.5 18.5L7 17M17 7l1.5-1.5") }
    val Chat: ImageVector by lazy { icon("chat", "M6.5 4.5h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H11l-4.5 3.5v-3.5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z") }
    val Rings: ImageVector by lazy { icon("rings", "M3.8 12a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0 -10.4 0M9.8 12a5.2 5.2 0 1 0 10.4 0a5.2 5.2 0 1 0 -10.4 0") }
    val User: ImageVector by lazy { icon("user", "M8.4 8.5a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0M5 20c1-4 3.8-6 7-6s6 2 7 6") }
    val Tag: ImageVector by lazy { icon("tag", "M4 4.5h7.5l8.5 8.5-7 7-8.5-8.5zM7.2 9a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0") }
    val Spark: ImageVector by lazy { icon("spark", "M12 3c.7 5.2 3.8 8.3 9 9-5.2.7-8.3 3.8-9 9-.7-5.2-3.8-8.3-9-9 5.2-.7 8.3-3.8 9-9z") }
    val Moon: ImageVector by lazy { icon("moon", "M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z") }
    val Rain: ImageVector by lazy { icon("rain", "M7 14.5h10a4 4 0 0 0 .6-7.95A5.5 5.5 0 0 0 7 6a4.3 4.3 0 0 0 0 8.5zM9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5") }
    val Wave: ImageVector by lazy { icon("wave", "M3 10c2-2 4-2 6 0s4 2 6 0 4-2 6 0M3 15c2-2 4-2 6 0s4 2 6 0 4-2 6 0") }
    val Drop: ImageVector by lazy { icon("drop", "M12 4c3 4 6 7.2 6 10.5a6 6 0 0 1-12 0C6 11.2 9 8 12 4z") }
    val Flame: ImageVector by lazy { icon("flame", "M12 3c1 3.5 5 5.5 5 10a5 5 0 0 1-10 0c0-2.5 1.5-4 2.5-5 .3 1.6 1 2.5 2 3 .5-3-.5-5.5.5-8z") }
    val Scribble: ImageVector by lazy { icon("scribble", "M4 14c2-4 3 3 5-1s3 3 5-1 3 3 6-2") }
    val Heart: ImageVector by lazy { icon("heart", "M12 19.5s-7-4.3-7-9.5a3.8 3.8 0 0 1 7-2.1A3.8 3.8 0 0 1 19 10c0 5.2-7 9.5-7 9.5z") }
    val Here: ImageVector by lazy { icon("here", "M12 21s6-5.4 6-10.5A6 6 0 0 0 6 10.5C6 15.6 12 21 12 21zM9.8 10.5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0") }
    val Hourglass: ImageVector by lazy { icon("hourglass", "M7 3.5h10M7 20.5h10M8 3.5c0 4.5 4 5.5 4 8.5s-4 4-4 8.5M16 3.5c0 4.5-4 5.5-4 8.5s4 4 4 8.5") }
    val Stop: ImageVector by lazy { icon("stop", "M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z") }
    val Refresh: ImageVector by lazy { icon("refresh", "M19 12a7 7 0 1 1-2.1-5M19 4.5V9h-4.5") }
    val Arrow: ImageVector by lazy { icon("arrow", "M5 12h14M13 6l6 6-6 6") }
    // ── 生成区结束 ──

    val Forward: ImageVector get() = ChevronRight
    val Down: ImageVector by lazy { stroke("down", "M12 5v14M6 13l6 6 6-6", width = 1.5f) }
    val File: ImageVector by lazy { stroke("file", "M7 3h7l5 5v13H7zM14 3v5h5") }
    val Close: ImageVector by lazy { stroke("close", "M6 6l12 12M18 6L6 18") }
    /** 字号与行距 */
    val TextSize: ImageVector by lazy { stroke("text-size", "M3 19l5-13 5 13M5 14.5h6M14 19l3.5-8 3.5 8M15.2 16.5h4.6") }
    /** 大纲 */
    val Outline: ImageVector by lazy { stroke("outline", "M4 6h16M8 12h12M8 18h12M4 12h.01M4 18h.01") }

    // ── 写作格式按钮（P9-01） ──
    val Heading: ImageVector by lazy { stroke("heading", "M6 5v14M17 5v14M6 12h11", width = 1.4f) }
    val Bold: ImageVector by lazy { stroke("bold", "M7 5h5.5a3.5 3.5 0 0 1 0 7H7zM7 12h6.5a3.5 3.5 0 0 1 0 7H7z", width = 1.6f) }
    val TaskList: ImageVector by lazy { stroke("task-list", "M4 5h5v5H4zM5.2 7.6l1.2 1.2 2-2.2M13 7.5h7M4 14h5v5H4zM13 16.5h7") }
    val Rule: ImageVector by lazy { stroke("rule", "M4 12h16M8 7h8M8 17h8") }
    val Comment: ImageVector by lazy { stroke("comment", "M5 5h14v10H10l-4 4v-4H5zM8.5 9h7M8.5 12h4") }

    /** 多条 path 组成的图标；`"d" to 2.4f` 表示这一条用单独的线宽 */
    private fun icon(name: String, vararg parts: Any): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        parts.forEach { part ->
            val (d, w) = if (part is Pair<*, *>) part.first as String to part.second as Float else part as String to 1.5f
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun stroke(name: String, pathData: String, width: Float = 1.5f): ImageVector =
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
