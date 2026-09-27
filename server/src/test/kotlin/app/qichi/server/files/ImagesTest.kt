package app.qichi.server.files

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 缩略图的内存上限（B3）：步长只按宽度算时，长截图会整张解出来。 */
class ImagesTest {

    @Test
    fun `长截图：解码按总像素加大步长，缩略图不超过上限、比例不变`() {
        val dir = Files.createTempDirectory("qichi-images-")
        val source = dir.resolve("tall.png")
        // 200 × 40000：按宽度算步长是 1，会把 800 万像素整张解出来
        val tall = BufferedImage(200, 40_000, BufferedImage.TYPE_BYTE_GRAY)
        ImageIO.write(tall, "png", source.toFile())

        val thumb = dir.resolve("tall.w200.jpg")
        assertTrue(Images.writeThumbnail(source, 200, thumb))
        val out = ImageIO.read(thumb.toFile())
        assertTrue(out.width.toLong() * out.height <= Images.MAX_DECODE_PIXELS, "缩略图 ${out.width}×${out.height} 超过上限")
        assertEquals(200.0 / 40_000, out.width.toDouble() / out.height, 0.001)
    }

    @Test
    fun `普通照片的步长不变：解出来约为缩略图宽的两倍`() {
        assertEquals(2, Images.subsampling(4000, 3000, 800))
        assertEquals(10, Images.subsampling(4000, 3000, 200))
        assertEquals(1, Images.subsampling(1200, 900, 400))
    }

    @Test
    fun `又窄又长或特别大的图：解出来的总像素不超过上限`() {
        for ((w, h) in listOf(1080 to 30_000, 200 to 40_000, 1 to Int.MAX_VALUE, 16_383 to 16_383, 60_000 to 60_000)) {
            for (width in listOf(200, 400, 800)) {
                val step = Images.subsampling(w, h, width)
                val pixels = ((w + step - 1) / step).toLong() * ((h + step - 1) / step)
                assertTrue(pixels <= Images.MAX_DECODE_PIXELS, "$w×$h 缩到 $width：步长 $step 解出 $pixels 像素")
            }
        }
    }
}
