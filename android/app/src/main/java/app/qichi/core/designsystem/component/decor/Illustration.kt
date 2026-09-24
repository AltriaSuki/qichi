package app.qichi.core.designsystem.component.decor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 七幅占位插画（没有照片时的封面、空状态）：海边、窗台绿萝、书架、雪山小屋、一桌饭、雨夜车站、雾海。 */
enum class Scene(val label: String) {
    Sea("海边"), Window("窗台绿萝"), Shelf("书架"), Snow("雪山小屋"), Dinner("一桌饭"), Rain("雨夜车站"), Fog("雾海"),
}

private fun c(hex: Long, alpha: Float = 1f) = Color(0xFF000000 or hex).copy(alpha = alpha)

/**
 * 占位插画：设计稿 gen_screens.py 的 scene()，300×300 的画按 slice 铺满（短边对齐）。颜色固定——它们是「照片」，不随天色变。
 * [description] 不为空时读屏会读出来（封面图）；为空时当装饰跳过。
 */
@Composable
fun Illustration(scene: Scene, modifier: Modifier = Modifier, shape: Shape = RectangleShape, description: String? = null) {
    val sem = if (description == null) Modifier.decorative() else Modifier.semantics { contentDescription = description }
    Canvas(modifier.clip(shape).then(sem)) {
        inViewBox(300f, 300f) {
            when (scene) {
                Scene.Sea -> sea()
                Scene.Window -> window()
                Scene.Shelf -> shelf()
                Scene.Snow -> snow()
                Scene.Dinner -> dinner()
                Scene.Rain -> rain()
                Scene.Fog -> fog()
            }
        }
    }
}

private fun DrawScope.rect(x: Float, y: Float, w: Float, h: Float, color: Color, rx: Float = 0f) =
    if (rx == 0f) drawRect(color, Offset(x, y), Size(w, h)) else drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(rx))

private fun DrawScope.path(d: String, color: Color) = drawPath(svgPath(d), color)

private fun DrawScope.stroke(d: String, color: Color, width: Float, round: Boolean = true) =
    drawPath(svgPath(d), color, style = Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round))

private fun DrawScope.vGradient(top: Long, bottom: Long) = drawRect(Brush.verticalGradient(listOf(c(top), c(bottom)), 0f, 300f), size = Size(300f, 300f))

/** 心形叶子（窗台、书架上的绿萝） */
private fun DrawScope.plantLeaf(x: Float, y: Float, a: Float) = translate(x, y) {
    rotate(a, pivot = Offset.Zero) { path("M0 0C-5 2-12 0-12-8C-12-15-5-19 0-25C5-19 12-15 12-8C12 0 5 2 0 0Z", c(0x7F9C7B)) }
}

private fun DrawScope.sea() {
    vGradient(0xCBD9E0, 0xF3EEE5)
    drawCircle(c(0xF8E8D1), 34f, Offset(206f, 112f)); drawCircle(c(0xF8E8D1, .35f), 52f, Offset(206f, 112f))
    rect(0f, 168f, 300f, 70f, c(0x9DB2BB)); rect(0f, 198f, 300f, 40f, c(0x86A0AB))
    stroke("M0 180h300M24 206h60M142 213h92M40 224h44M200 196h70", c(0xFFFFFF, .55f), 2.4f)
    path("M0 238c60-16 120-16 180-5s90 10 120 4V300H0z", c(0xEADFCB))
    drawCircle(c(0x3B4248), 4.2f, Offset(146f, 246f)); rect(142f, 250f, 8.4f, 17f, c(0x3B4248), 3.5f)
    drawCircle(c(0x3B4248), 3.8f, Offset(160f, 247f)); rect(156.3f, 251f, 7.4f, 16f, c(0x3B4248), 3.2f)
    stroke("M60 60l8 4-8 4M84 44l6 3-6 3", c(0x6D7F88), 1.6f)
}

private fun DrawScope.window() {
    rect(0f, 0f, 300f, 300f, c(0xECE3D7)); rect(18f, 18f, 40f, 230f, c(0xE1D2C1))
    rect(70f, 36f, 176f, 150f, c(0xDCE6EA)); path("M70 150c40-20 80-6 120-16s46-8 56-6V186H70z", c(0xC3CFD3))
    drawRect(c(0xFFFFFF), Offset(70f, 36f), Size(176f, 150f), style = Stroke(9f))
    stroke("M158 36v150M70 111h176", c(0xFFFFFF), 6f, round = false)
    rect(50f, 186f, 220f, 12f, c(0xFFFFFF)); path("M126 150h48l-6 36h-36z", c(0xB98A6E))
    stroke("M150 150c-10-22-34-26-44-18M150 150c8-24 30-30 42-20M150 150c0-26-8-40-2-54M132 186c-10 30-6 56 8 84", c(0x6E8A6B), 2f)
    listOf(Triple(108f, 134f, -60f), Triple(190f, 130f, 60f), Triple(148f, 98f, 0f), Triple(126f, 212f, -30f), Triple(138f, 244f, 20f),
        Triple(132f, 270f, -10f), Triple(170f, 128f, 10f), Triple(118f, 150f, -100f)).forEach { (x, y, a) -> plantLeaf(x, y, a) }
}

private fun DrawScope.shelf() {
    rect(0f, 0f, 300f, 300f, c(0xE9E2D6))
    val cols = listOf(0x9A5552L, 0x4F6B7AL, 0xD6C3A3L, 0x7F8C79L, 0xC9A98CL, 0x5D6167L, 0xB8876FL)
    var x = 36f
    listOf(20f to 70f, 26f to 80f, 18f to 64f, 24f to 76f, 22f to 70f, 16f to 60f, 26f to 82f).forEachIndexed { i, (w, h) ->
        rect(x, 118f - h, w, h, c(cols[i]), 2f)
        rect(x + 3f, 118f - h + 10f, w - 6f, 3f, c(0xFFFFFF, .45f))
        x += w + 3f
    }
    rotate(14f, pivot = Offset(244f, 118f)) { rect(238f, 44f, 12f, 74f, c(0x7F8C79), 2f) }
    rect(20f, 118f, 260f, 9f, c(0xA98C6F)); rect(20f, 228f, 260f, 9f, c(0xA98C6F))
    rect(46f, 176f, 46f, 52f, c(0xFFFFFF), 2f); drawRoundRect(c(0xC9B79C), Offset(46f, 176f), Size(46f, 52f), CornerRadius(2f), style = Stroke(4f))
    path("M54 216l10-12 8 8 6-6 8 10z", c(0x9DB2BB)); path("M180 196h38l-4 32h-30z", c(0xB98A6E))
    listOf(Triple(199f, 196f, 0f), Triple(186f, 198f, -50f), Triple(212f, 198f, 50f), Triple(200f, 176f, 10f)).forEach { (px, py, a) -> plantLeaf(px, py, a) }
}

private fun DrawScope.snow() {
    vGradient(0xC9D4DC, 0xEEF1F2)
    path("M0 170l70-80 50 50 60-80 120 110V300H0z", c(0xB8C4CA)); path("M130 110l50-50 40 38-18-6-14 12-16-10z", c(0xFFFFFF))
    path("M40 130l30-40 22 22-12-4-10 10z", c(0xFFFFFF)); path("M0 206c80-20 180-24 300-8V300H0z", c(0xF6F7F5))
    rect(118f, 178f, 70f, 46f, c(0x8A6A56)); path("M110 182l43-34 43 34z", c(0x5B4A40))
    stroke("M110 182l43-34 43 34", c(0xFFFFFF), 4f)
    rect(130f, 192f, 16f, 14f, c(0xF1D29A)); rect(160f, 196f, 14f, 28f, c(0x5B4A40))
    path("M218 226l14-40 14 40zM240 230l12-34 12 34zM58 230l12-34 12 34z", c(0x6E8277))
    listOf(Triple(30f, 40f, 2f), Triple(90f, 70f, 1.6f), Triple(160f, 30f, 2.2f), Triple(250f, 60f, 1.8f), Triple(210f, 120f, 1.6f),
        Triple(60f, 150f, 2f), Triple(270f, 150f, 2f), Triple(120f, 90f, 1.4f)).forEach { (x, y, r) -> drawCircle(c(0xFFFFFF, .9f), r, Offset(x, y)) }
}

private fun DrawScope.dinner() {
    rect(0f, 0f, 300f, 300f, c(0xE9DFCF))
    listOf(30f, 90f, 150f, 210f, 270f).forEach { x -> rect(x - 5f, 0f, 10f, 300f, c(0xDCCDB6)) }
    drawCircle(c(0xFFFFFF), 84f, Offset(150f, 150f)); drawCircle(c(0xF4EFE7), 66f, Offset(150f, 150f))
    rect(112f, 122f, 30f, 24f, c(0x8E4A3A), 7f); rect(146f, 118f, 32f, 26f, c(0x9A5242), 7f)
    rect(124f, 150f, 30f, 24f, c(0x83412F), 7f); rect(158f, 148f, 28f, 24f, c(0x944B3B), 7f)
    drawCircle(c(0x7F9C7B), 3f, Offset(138f, 130f)); drawCircle(c(0x7F9C7B), 3f, Offset(170f, 160f))
    stroke("M40 250l90-60M52 262l90-60", c(0x7A5A44), 5f)
    drawCircle(c(0xFFFFFF), 26f, Offset(250f, 60f)); drawCircle(c(0x4F6B7A, .25f), 18f, Offset(250f, 60f))
    drawCircle(c(0xF6E3BF, .7f), 30f, Offset(54f, 56f)); rect(48f, 44f, 12f, 26f, c(0xFFFFFF), 3f)
}

private fun DrawScope.rain() {
    rect(0f, 0f, 300f, 300f, c(0x2B3440))
    listOf(floatArrayOf(60f, 80f, 26f, .25f), floatArrayOf(60f, 80f, 8f, .9f), floatArrayOf(180f, 60f, 30f, .2f), floatArrayOf(180f, 60f, 9f, .85f),
        floatArrayOf(260f, 110f, 22f, .2f), floatArrayOf(260f, 110f, 7f, .8f)).forEach { (x, y, r, o) -> drawCircle(c(0xF2D9A6, o), r, Offset(x, y)) }
    rect(0f, 210f, 300f, 90f, c(0x222A34))
    stroke("M40 230h40M150 250h60M230 236h30", c(0xF2D9A6, .35f), 3f, round = false)
    listOf(20f to 20f, 70f to 140f, 120f to 40f, 150f to 170f, 200f to 110f, 240f to 20f, 280f to 180f, 100f to 100f, 230f to 160f)
        .forEach { (x, y) -> drawLine(c(0x9FB0BD, .5f), Offset(x, y), Offset(x - 8f, y + 22f), 1.4f) }
    path("M124 196a30 30 0 0 1 60 0z", c(0x9A5552))
    drawLine(c(0x1B2027), Offset(154f, 196f), Offset(154f, 230f), 3f)
    rect(146f, 204f, 16f, 34f, c(0x1B2027), 6f)
}

private fun DrawScope.fog() {
    vGradient(0xD3D9DC, 0xEFEDE7)
    drawCircle(c(0xFFF9EE), 22f, Offset(214f, 96f))
    path("M0 150c22-12 40-22 62-18s34-14 58-8 38 20 64 8 42-22 66-12 28 12 50 8V300H0z", c(0xB8BFC1))
    path("M0 190c30-12 52-20 80-14s44 12 70-2 46-14 74-2 42 14 76 8V300H0z", c(0x9CA5A8))
    path("M0 224c26-8 56-14 84-6s56 12 86 0 58-10 86-2 26 6 44 4V300H0z", c(0x7E898D))
}
