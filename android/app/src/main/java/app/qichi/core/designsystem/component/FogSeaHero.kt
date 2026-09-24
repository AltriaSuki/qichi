package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.core.designsystem.colorsFor

/** 雾海插画的两种构图。 */
enum class HeroLayout {
    /** 今天页顶部整幅（画布 390×430）：人影在右下，底部渐隐到页面底色，日期叠在上面 */
    Wide,

    /** 方一点的小图（画布 290×300）：登录页、房间设置里的主视觉预览、照片占位 */
    Compact,
}

/**
 * 雾海插画（房间没有设照片时的主视觉）：天空、日或月、三层远山与雾、岩石上的两个人。
 * 按天色换配色，图形取自设计稿 Main / Sky-*.dc.html 的 SVG，按「居中裁切」铺满。
 */
@Composable
fun FogSeaHero(modifier: Modifier = Modifier, sky: Sky = QichiTheme.sky, layout: HeroLayout = HeroLayout.Compact) {
    val palette = heroPalette(sky)
    val geometry = if (layout == HeroLayout.Wide) WIDE else COMPACT
    val background = colorsFor(sky).background
    // 居中裁切：放大铺满后超出的部分要裁掉，不能画到框外
    Canvas(modifier.clipToBounds().semantics { contentDescription = "雾海" }) {
        val scale = maxOf(size.width / geometry.width, size.height / geometry.height)
        val dx = (size.width - geometry.width * scale) / 2
        // 宽版贴着底边对齐：底部渐隐到页面底色的那一截不能被裁掉，否则和下面的内容之间会有一道边
        val dy = if (geometry.wide) size.height - geometry.height * scale else (size.height - geometry.height * scale) / 2
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // 顶部被裁掉多少（画布坐标）
            val lift = if (geometry.wide) (-dy / scale).coerceAtLeast(0f) else 0f
            drawHero(palette, geometry, sky, background, lift)
        }
    }
}

private data class HeroPalette(
    val skyTop: Color,
    val skyBottom: Color,
    val fog: Color,
    val glow: Color,
    val sunAlpha: Float,
    val mountains: List<Color>,
    val rock: Color,
    val figure: Color,
)

private fun heroPalette(sky: Sky): HeroPalette = when (sky) {
    Sky.Dawn -> HeroPalette(
        Color(0xFFD9D3DC), Color(0xFFF4DDD3), Color(0xFFF5E7E2), Color(0xFFF0B09E), 0.95f,
        listOf(Color(0xFFC9BAC0), Color(0xFFAE9EA8), Color(0xFF8F828F)), Color(0xFF3C393F), Color(0xFF1F1D22),
    )
    Sky.Day -> HeroPalette(
        Color(0xFFD3D9DC), Color(0xFFEFEDE7), Color(0xFFEFEEE9), Color(0xFFFFF9EE), 0.9f,
        listOf(Color(0xFFB8BFC1), Color(0xFF9CA5A8), Color(0xFF7E898D)), Color(0xFF363C3B), Color(0xFF1C2120),
    )
    Sky.Dusk -> HeroPalette(
        Color(0xFFCFC1B4), Color(0xFFEFCBA0), Color(0xFFF1DEC6), Color(0xFFE7A564), 0.95f,
        listOf(Color(0xFFBDA492), Color(0xFFA08876), Color(0xFF806B5C)), Color(0xFF3E352F), Color(0xFF1E1916),
    )
    Sky.Night -> HeroPalette(
        Color(0xFF0F141B), Color(0xFF2A3441), Color(0xFF3E4A57), Color(0xFFECE9DF), 1f,
        listOf(Color(0xFF3A4552), Color(0xFF2E3844), Color(0xFF232C36)), Color(0xFF0E1216), Color(0xFF07090B),
    )
}

/** 太阳 / 月亮的位置、大小（各天色不同）。 */
private data class Sun(val center: Offset, val radius: Float, val glowRadius: Float)

private class Figure(val head: Offset, val headRadius: Float, val body: Path)

private class HeroGeometry(
    val width: Float,
    val height: Float,
    /** 天空渐变在哪里到底色（0–1） */
    val skyStop: Float,
    val glowAlpha: Float,
    val suns: Map<Sky, Sun>,
    /** 远山和盖在它上面的雾带（雾带上沿、高度） */
    val mountains: List<Pair<Path, Pair<Float, Float>>>,
    /** 最下面整片雾的上沿 */
    val fogTop: Float,
    val rock: Path,
    val figures: List<Figure>,
    /** 第一个人手里的「伞 / 手杖」那一笔 */
    val stroke: Pair<Offset, Offset>,
    val strokeWidth: Float,
    val stars: List<Triple<Float, Float, Float>>,
    /** 宽版：人影前面一片椭圆的雾，底部渐隐到页面底色 */
    val wide: Boolean,
)

private fun path(d: String) = PathParser().parsePathString(d).toPath()

private val COMPACT = HeroGeometry(
    width = 290f, height = 300f, skyStop = 1f, glowAlpha = 0.45f,
    suns = mapOf(
        Sky.Dawn to Sun(Offset(96f, 182f), 20f, 68f),
        Sky.Day to Sun(Offset(214f, 96f), 22f, 75f),
        Sky.Dusk to Sun(Offset(206f, 170f), 24f, 82f),
        Sky.Night to Sun(Offset(206f, 74f), 15f, 51f),
    ),
    mountains = listOf(
        path("M0 150C22 138 40 128 62 132S96 112 120 118S158 138 184 126S226 104 250 114S278 126 290 122V300H0z") to (118f to 50f),
        path("M0 182C30 170 52 162 80 168S124 180 150 166S196 152 224 164S266 178 290 172V300H0z") to (160f to 42f),
        path("M0 206C26 198 56 192 84 200S140 212 170 200S228 190 256 198S282 204 290 202V300H0z") to (188f to 40f),
    ),
    fogTop = 224f,
    rock = path("M110 300C120 280 132 266 148 258S176 246 190 244L206 243C214 246 220 252 228 262S250 286 262 300Z"),
    figures = listOf(
        Figure(Offset(192f, 223.5f), 2.4f, path("M187.8 244.5L189.2 227.7Q192 224.3 194.8 227.7L196.2 244.5Z")),
        Figure(Offset(203f, 225.7f), 2.1f, path("M198.8 244L200.2 229.3Q203 226.4 205.8 229.3L207.2 244Z")),
    ),
    stroke = Offset(197f, 244.5f) to Offset(199.5f, 231.3f), strokeWidth = 1f,
    stars = listOf(
        Triple(30f, 24f, 0.8f), Triple(64f, 58f, 0.5f), Triple(110f, 20f, 0.7f), Triple(150f, 44f, 0.45f),
        Triple(250f, 30f, 0.7f), Triple(272f, 96f, 0.4f), Triple(22f, 90f, 0.4f), Triple(130f, 80f, 0.35f),
        Triple(186f, 18f, 0.6f),
    ),
    wide = false,
)

private val WIDE = HeroGeometry(
    width = 390f, height = 430f, skyStop = 0.72f, glowAlpha = 0.5f,
    suns = mapOf(
        Sky.Dawn to Sun(Offset(92f, 262f), 22f, 79f),
        Sky.Day to Sun(Offset(292f, 118f), 24f, 86f),
        Sky.Dusk to Sun(Offset(236f, 246f), 28f, 101f),
        Sky.Night to Sun(Offset(296f, 96f), 16f, 58f),
    ),
    mountains = listOf(
        path("M0 262C40 246 70 236 104 240S160 214 196 222S250 250 290 236S350 212 390 222V430H0z") to (226f to 64f),
        path("M0 296C44 282 84 272 124 280S188 298 226 282S300 262 340 276S378 290 390 286V430H0z") to (268f to 52f),
        path("M0 324C36 314 80 308 120 316S200 332 246 320S330 306 390 318V430H0z") to (300f to 46f),
    ),
    fogTop = 340f,
    rock = path("M250 430C262 406 278 390 298 378S326 360 336 356L352 355C360 358 368 366 376 378S388 402 390 412V430Z"),
    figures = listOf(
        Figure(Offset(338f, 329.9f), 2.9f, path("M333.2 356.5L335.0 336.1Q338 331.6 341.0 336.1L342.8 356.5Z")),
        Figure(Offset(349f, 331.7f), 2.6f, path("M344.7 355.6L346.3 337.2Q349 333.2 351.7 337.2L353.3 355.6Z")),
    ),
    stroke = Offset(354.4f, 355.6f) to Offset(357.1f, 340.5f), strokeWidth = 1.2f,
    stars = listOf(
        Triple(34f, 40f, 0.8f), Triple(78f, 88f, 0.5f), Triple(130f, 30f, 0.7f), Triple(176f, 64f, 0.45f),
        Triple(222f, 24f, 0.6f), Triple(352f, 48f, 0.7f), Triple(366f, 150f, 0.4f), Triple(24f, 132f, 0.4f),
        Triple(150f, 118f, 0.35f), Triple(250f, 160f, 0.3f), Triple(104f, 176f, 0.3f),
    ),
    wide = true,
)

private fun DrawScope.drawHero(p: HeroPalette, g: HeroGeometry, sky: Sky, background: Color, lift: Float = 0f) {
    val w = g.width
    val h = g.height
    drawRect(Brush.verticalGradient(0f to p.skyTop, g.skyStop to p.skyBottom, startY = 0f, endY = h), size = Size(w, h))
    if (sky == Sky.Night) g.stars.forEach { (x, y, a) -> drawCircle(p.glow.copy(alpha = a), 0.9f, Offset(x, y)) }
    // 宽版贴底对齐时，屏幕比设计稿宽，整幅图会往上移：日月跟着下移同样的距离，不和右上角的两人标记挤在一起
    val sun = g.suns.getValue(sky).let { if (g.wide) it.copy(center = it.center + Offset(0f, lift)) else it }
    drawCircle(
        Brush.radialGradient(listOf(p.glow.copy(alpha = g.glowAlpha), p.glow.copy(alpha = 0f)), sun.center, sun.glowRadius),
        sun.glowRadius,
        sun.center,
    )
    drawCircle(p.glow.copy(alpha = p.sunAlpha), sun.radius, sun.center)

    g.mountains.forEachIndexed { i, (path, fog) ->
        drawPath(path, p.mountains[i])
        val (top, height) = fog
        drawRect(
            Brush.verticalGradient(listOf(p.fog.copy(alpha = 0f), p.fog), top, top + height),
            topLeft = Offset(0f, top),
            size = Size(w, height),
            alpha = 0.95f,
        )
    }
    drawRect(p.fog, topLeft = Offset(0f, g.fogTop), size = Size(w, h - g.fogTop))
    drawPath(g.rock, p.rock)

    // 岩石上的两个人
    g.figures.forEach { f ->
        drawCircle(p.figure, f.headRadius, f.head)
        drawPath(f.body, p.figure)
    }
    drawLine(p.figure, g.stroke.first, g.stroke.second, strokeWidth = g.strokeWidth, cap = StrokeCap.Round)

    if (g.wide) {
        drawOval(p.fog.copy(alpha = 0.75f), topLeft = Offset(330f - 120f, 408f - 16f), size = Size(240f, 32f))
        // 底部渐隐到页面底色，下面的内容接得上
        drawRect(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), 320f, h), topLeft = Offset(0f, 320f), size = Size(w, h - 320f))
    }
}
