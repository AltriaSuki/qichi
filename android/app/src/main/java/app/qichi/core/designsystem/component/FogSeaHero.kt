package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky

/**
 * 今天页主视觉的雾海插画（没有照片时显示）：天空、日或月、三层远山与雾、岩石上的两个人。
 * 按天色换配色，图形取自设计稿 Main / Sky-*.dc.html 的 SVG（画布 290×300，按「居中裁切」铺满）。
 */
@Composable
fun FogSeaHero(modifier: Modifier = Modifier, sky: Sky = QichiTheme.sky) {
    val palette = heroPalette(sky)
    Canvas(modifier.semantics { contentDescription = "雾海" }) {
        val scale = maxOf(size.width / VIEW_W, size.height / VIEW_H)
        val dx = (size.width - VIEW_W * scale) / 2
        val dy = (size.height - VIEW_H * scale) / 2
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawHero(palette)
        }
    }
}

private const val VIEW_W = 290f
private const val VIEW_H = 300f

private data class HeroPalette(
    val skyTop: Color,
    val skyBottom: Color,
    val fog: Color,
    val glow: Color,
    val sun: Color,
    val sunAlpha: Float,
    val sunCenter: Offset,
    val sunRadius: Float,
    val glowRadius: Float,
    val mountains: List<Color>,
    val rock: Color,
    val figure: Color,
    val stars: Boolean = false,
)

private fun heroPalette(sky: Sky): HeroPalette = when (sky) {
    Sky.Dawn -> HeroPalette(
        Color(0xFFD9D3DC), Color(0xFFF4DDD3), Color(0xFFF5E7E2), Color(0xFFF0B09E), Color(0xFFF0B09E), 0.95f,
        Offset(96f, 182f), 20f, 68f, listOf(Color(0xFFC9BAC0), Color(0xFFAE9EA8), Color(0xFF8F828F)),
        Color(0xFF3C393F), Color(0xFF1F1D22),
    )
    Sky.Day -> HeroPalette(
        Color(0xFFD6DCDE), Color(0xFFEEF0EE), Color(0xFFEEF0EE), Color.White, Color.White, 0.75f,
        Offset(214f, 96f), 22f, 75f, listOf(Color(0xFFBAC3C6), Color(0xFF9DA9AD), Color(0xFF7F8D92)),
        Color(0xFF39413F), Color(0xFF1D2423),
    )
    Sky.Dusk -> HeroPalette(
        Color(0xFFCFC1B4), Color(0xFFEFCBA0), Color(0xFFF1DEC6), Color(0xFFE7A564), Color(0xFFE7A564), 0.95f,
        Offset(206f, 170f), 24f, 82f, listOf(Color(0xFFBDA492), Color(0xFFA08876), Color(0xFF806B5C)),
        Color(0xFF3E352F), Color(0xFF1E1916),
    )
    Sky.Night -> HeroPalette(
        Color(0xFF0F141B), Color(0xFF2A3441), Color(0xFF3E4A57), Color(0xFFECE9DF), Color(0xFFECE9DF), 1f,
        Offset(206f, 74f), 15f, 51f, listOf(Color(0xFF3A4552), Color(0xFF2E3844), Color(0xFF232C36)),
        Color(0xFF0E1216), Color(0xFF07090B), stars = true,
    )
}

private val MOUNTAINS = listOf(
    "M0 150C22 138 40 128 62 132S96 112 120 118S158 138 184 126S226 104 250 114S278 126 290 122V300H0z" to (118f to 50f),
    "M0 182C30 170 52 162 80 168S124 180 150 166S196 152 224 164S266 178 290 172V300H0z" to (160f to 42f),
    "M0 206C26 198 56 192 84 200S140 212 170 200S228 190 256 198S282 204 290 202V300H0z" to (188f to 40f),
).map { (d, fog) -> PathParser().parsePathString(d).toPath() to fog }

private val ROCK = PathParser()
    .parsePathString("M110 300C120 280 132 266 148 258S176 246 190 244L206 243C214 246 220 252 228 262S250 286 262 300Z")
    .toPath()

private val FIGURES = listOf(
    "M187.8 244.5L189.2 227.7Q192 224.3 194.8 227.7L196.2 244.5Z",
    "M198.8 244L200.2 229.3Q203 226.4 205.8 229.3L207.2 244Z",
).map { PathParser().parsePathString(it).toPath() }

private val STARS = listOf(
    Triple(30f, 24f, 0.8f), Triple(64f, 58f, 0.5f), Triple(110f, 20f, 0.7f), Triple(150f, 44f, 0.45f),
    Triple(250f, 30f, 0.7f), Triple(272f, 96f, 0.4f), Triple(22f, 90f, 0.4f), Triple(130f, 80f, 0.35f),
    Triple(186f, 18f, 0.6f),
)

private fun DrawScope.drawHero(p: HeroPalette) {
    drawRect(Brush.verticalGradient(listOf(p.skyTop, p.skyBottom), 0f, VIEW_H), size = Size(VIEW_W, VIEW_H))
    if (p.stars) STARS.forEach { (x, y, a) -> drawCircle(p.glow.copy(alpha = a), 0.8f, Offset(x, y)) }
    drawCircle(
        Brush.radialGradient(listOf(p.glow.copy(alpha = 0.45f), p.glow.copy(alpha = 0f)), p.sunCenter, p.glowRadius),
        p.glowRadius,
        p.sunCenter,
    )
    drawCircle(p.sun.copy(alpha = p.sunAlpha), p.sunRadius, p.sunCenter)

    MOUNTAINS.forEachIndexed { i, (path, fog) ->
        drawPath(path, p.mountains[i])
        val (top, height) = fog
        drawRect(
            Brush.verticalGradient(listOf(p.fog.copy(alpha = 0f), p.fog), top, top + height),
            topLeft = Offset(0f, top),
            size = Size(VIEW_W, height),
            alpha = 0.95f,
        )
    }
    drawRect(p.fog, topLeft = Offset(0f, 224f), size = Size(VIEW_W, 76f))
    drawPath(ROCK, p.rock)

    // 岩石上的两个人
    drawCircle(p.figure, 2.4f, Offset(192f, 223.5f))
    drawPath(FIGURES[0], p.figure)
    drawLine(p.figure, Offset(197f, 244.5f), Offset(199.5f, 231.3f), strokeWidth = 1f, cap = StrokeCap.Round)
    drawCircle(p.figure, 2.1f, Offset(203f, 225.7f))
    drawPath(FIGURES[1], p.figure)
}
