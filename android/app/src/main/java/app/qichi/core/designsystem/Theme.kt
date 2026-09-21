package app.qichi.core.designsystem

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.time.LocalTime

val LocalQichiColors = staticCompositionLocalOf { DayColors }
val LocalQichiTypography = staticCompositionLocalOf { DefaultTypography }
val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalSky = staticCompositionLocalOf { Sky.Day }

/** 在任何 Composable 里用 `QichiTheme.colors.ink` 这样取设计令牌。 */
object QichiTheme {
    val colors: QichiColors @Composable get() = LocalQichiColors.current
    val typography: QichiTypography @Composable get() = LocalQichiTypography.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
    val sky: Sky @Composable get() = LocalSky.current
}

/** 天色切换的淡入时长（「减少动画」时直接切换）。 */
const val SKY_TRANSITION_MILLIS = 800

/**
 * 栖迟主题。
 * @param skyOverride 固定某个天色（组件陈列页、截图用）；为空时按手机本地时间，每分钟检查一次。
 * @param largeText 「大字」模式：字号 ×1.2
 * @param reduceMotion 「减少动画」：天色与页面切换都直接完成
 */
@Composable
fun QichiTheme(
    skyOverride: Sky? = null,
    largeText: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val sky = skyOverride ?: rememberCurrentSky()
    val target = colorsFor(sky)
    val colors = animatedColors(target, if (reduceMotion) snap() else tween(SKY_TRANSITION_MILLIS))
    val typography = remember(largeText) {
        if (largeText) DefaultTypography.scaled(QichiTypography.LARGE_TEXT_FACTOR) else DefaultTypography
    }

    SystemBarsAppearance(dark = target.isDark)

    CompositionLocalProvider(
        LocalQichiColors provides colors,
        LocalQichiTypography provides typography,
        LocalReduceMotion provides reduceMotion,
        LocalSky provides sky,
    ) {
        // Material 组件只作底层；配色由 QichiColors 映射，只为了让内置组件不出错
        MaterialTheme(colorScheme = colors.toMaterial(), content = content)
    }
}

/** 按手机本地时间得到当前天色，每到整分钟重新检查。 */
@Composable
fun rememberCurrentSky(now: () -> LocalTime = LocalTime::now): Sky {
    var sky by remember { mutableStateOf(skyAt(now())) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = now()
            delay((60 - t.second) * 1000L - t.nano / 1_000_000)
            sky = skyAt(now())
        }
    }
    return sky
}

@Composable
private fun animatedColors(target: QichiColors, spec: AnimationSpec<Color>): QichiColors {
    @Composable
    fun Color.animated(label: String) = animateColorAsState(this, spec, label = label).value
    return QichiColors(
        background = target.background.animated("background"),
        surface = target.surface.animated("surface"),
        paper = target.paper.animated("paper"),
        ink = target.ink.animated("ink"),
        muted = target.muted.animated("muted"),
        faint = target.faint.animated("faint"),
        line = target.line.animated("line"),
        line2 = target.line2.animated("line2"),
        accent = target.accent.animated("accent"),
        personA = target.personA.animated("personA"),
        personB = target.personB.animated("personB"),
        onPerson = target.onPerson.animated("onPerson"),
        isDark = target.isDark,
    )
}

/** 深夜时状态栏、导航栏图标用浅色，其余天色用深色。 */
@Composable
private fun SystemBarsAppearance(dark: Boolean) {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private fun QichiColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink,
        onPrimary = background,
        secondary = accent,
        onSecondary = background,
        tertiary = personB,
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = paper,
        onSurfaceVariant = muted,
        surfaceContainer = paper,
        surfaceContainerHigh = paper,
        surfaceContainerHighest = paper,
        surfaceContainerLow = background,
        surfaceContainerLowest = background,
        outline = line2,
        outlineVariant = line,
        error = accent,
        onError = background,
        scrim = Color.Black.copy(alpha = 0.32f),
    )
}
