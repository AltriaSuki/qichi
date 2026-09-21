package app.qichi.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.time.LocalTime

/** 四种天色，按手机本地时间切换（docs/06-design-system.md §2）。 */
enum class Sky { Dawn, Day, Dusk, Night }

/** 清晨 05:00–08:00，白天 08:00–17:00，黄昏 17:00–19:30，深夜 19:30–05:00。 */
fun skyAt(localTime: LocalTime): Sky = when {
    localTime < LocalTime.of(5, 0) -> Sky.Night
    localTime < LocalTime.of(8, 0) -> Sky.Dawn
    localTime < LocalTime.of(17, 0) -> Sky.Day
    localTime < LocalTime.of(19, 30) -> Sky.Dusk
    else -> Sky.Night
}

@Immutable
data class QichiColors(
    /** 页面底色 */
    val background: Color,
    /** 雾层卡片、输入框、气泡 */
    val surface: Color,
    /** 文稿纸、书页、信封 */
    val paper: Color,
    /** 正文 */
    val ink: Color,
    /** 次要文字（对比度 ≥ 4.5:1） */
    val muted: Color,
    /** 装饰、已完成项、占位——不用于需要阅读的文字 */
    val faint: Color,
    /** 极少量分隔 */
    val line: Color,
    /** 点线引导、未完成节点 */
    val line2: Color,
    /** 唯一的强调色：暮玫瑰 */
    val accent: Color,
    /** 房间创建者 */
    val personA: Color,
    /** 另一位成员；也用于 AI 标记 */
    val personB: Color,
    /** 人物圆标上的字 */
    val onPerson: Color,
    val isDark: Boolean,
)

private fun white(alpha: Float) = Color.White.copy(alpha = alpha)

val DawnColors = QichiColors(
    background = Color(0xFFEEE7E4), surface = white(0.60f), paper = Color(0xFFF7F2F0),
    ink = Color(0xFF2E2F38), muted = Color(0xFF655E66), faint = Color(0xFFA69BA0),
    line = Color(0xFFDDD3D1), line2 = Color(0xFFC8BCBC), accent = Color(0xFF9A5552),
    personA = Color(0xFFA8625F), personB = Color(0xFF4F6B7A), onPerson = Color.White,
    isDark = false,
)

val DayColors = QichiColors(
    background = Color(0xFFE9ECEA), surface = white(0.62f), paper = Color(0xFFF5F6F3),
    ink = Color(0xFF2B323A), muted = Color(0xFF5C646C), faint = Color(0xFF9CA3A7),
    line = Color(0xFFD6DBD9), line2 = Color(0xFFBEC5C5), accent = Color(0xFF9A5552),
    personA = Color(0xFFA8625F), personB = Color(0xFF4F6B7A), onPerson = Color.White,
    isDark = false,
)

val DuskColors = QichiColors(
    background = Color(0xFFECE3D6), surface = white(0.55f), paper = Color(0xFFF6F0E6),
    ink = Color(0xFF2F2B28), muted = Color(0xFF665D55), faint = Color(0xFFA89C8E),
    line = Color(0xFFDDD2C3), line2 = Color(0xFFC9BBA8), accent = Color(0xFF94523A),
    personA = Color(0xFFA55E45), personB = Color(0xFF4F6B7A), onPerson = Color.White,
    isDark = false,
)

val NightColors = QichiColors(
    background = Color(0xFF1B2027), surface = white(0.05f), paper = Color(0xFF222830),
    ink = Color(0xFFE3E6E4), muted = Color(0xFF9CA5AD), faint = Color(0xFF5F6973),
    line = Color(0xFF2B323B), line2 = Color(0xFF3A434D), accent = Color(0xFFD8A09C),
    personA = Color(0xFFD39A96), personB = Color(0xFF93AEBD), onPerson = Color(0xFF1B2027),
    isDark = true,
)

fun colorsFor(sky: Sky): QichiColors = when (sky) {
    Sky.Dawn -> DawnColors
    Sky.Day -> DayColors
    Sky.Dusk -> DuskColors
    Sky.Night -> NightColors
}
