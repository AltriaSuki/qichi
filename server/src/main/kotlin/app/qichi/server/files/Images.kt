package app.qichi.server.files

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/** 允许上传的图片格式。按文件头识别，不信任客户端声明的类型。 */
enum class ImageFormat(val mimeType: String) {
    Jpeg("image/jpeg"),
    Png("image/png"),
    Gif("image/gif"),
    WebP("image/webp"),
    Heic("image/heic"),
}

object Images {
    /** 缩略图允许的宽度（docs/04-api.md） */
    val THUMB_WIDTHS = setOf(200, 400, 800)
    private const val THUMB_QUALITY = 0.82f
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

    fun sniff(head: ByteArray): ImageFormat? {
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.Jpeg
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.Png
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageFormat.Gif
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageFormat.WebP
            ascii(4, "ftyp") && head.size >= 12 && String(head, 8, 4, Charsets.US_ASCII) in HEIC_BRANDS -> ImageFormat.Heic
            else -> null
        }
    }

    fun sniff(path: Path): ImageFormat? {
        val head = Files.newInputStream(path).use { it.readNBytes(32) }
        return sniff(head)
    }

    /** 宽高（只读文件头，不解码像素）。读不出来返回 null。 */
    fun dimensions(path: Path, format: ImageFormat): Pair<Int, Int>? = runCatching {
        if (format == ImageFormat.Heic) return heicDimensions(path)
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /**
     * HEIC 没有 Java 解码器，只从 `ispe` 盒子里读尺寸：取所有 ispe 里最大的一个
     * （网格图的每个小块也有 ispe，整张图的最大）。
     */
    private fun heicDimensions(path: Path): Pair<Int, Int>? {
        val bytes = Files.newInputStream(path).use { it.readNBytes(256 * 1024) }
        var best: Pair<Int, Int>? = null
        var i = 0
        while (i + 16 <= bytes.size) {
            if (bytes[i] == 'i'.code.toByte() && bytes[i + 1] == 's'.code.toByte() &&
                bytes[i + 2] == 'p'.code.toByte() && bytes[i + 3] == 'e'.code.toByte()
            ) {
                val w = int32(bytes, i + 8)
                val h = int32(bytes, i + 12)
                if (w > 0 && h > 0 && (best == null || w.toLong() * h > best.first.toLong() * best.second)) best = w to h
                i += 16
            } else {
                i++
            }
        }
        return best
    }

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /**
     * 生成宽 [width] 的 JPEG 缩略图写到 [target]（原图更窄时不放大）。
     * 解码时按比例降采样，大图也不会占用太多内存。解不开（如 HEIC）返回 false。
     */
    fun writeThumbnail(source: Path, width: Int, target: Path): Boolean {
        val image = ImageIO.createImageInputStream(source.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
            try {
                reader.input = input
                val param = reader.defaultReadParam
                val step = max(1, reader.getWidth(0) / (width * 2))
                param.setSourceSubsampling(step, step, 0, 0)
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
        val scaled = scaleToWidth(image, minOf(width, image.width))
        val temp = Files.createTempFile(target.parent, "thumb-", ".part")
        try {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(temp.toFile()).use { out ->
                    writer.output = out
                    val param = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = THUMB_QUALITY
                    }
                    writer.write(null, IIOImage(scaled, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return true
    }

    /** 先逐次减半再一次缩到目标宽，比一步缩放清晰；透明处填白色。 */
    private fun scaleToWidth(source: BufferedImage, width: Int): BufferedImage {
        val targetHeight = max(1, (source.height.toLong() * width / source.width).toInt())
        var image = redraw(source, source.width, source.height)
        while (image.width / 2 >= width) {
            image = redraw(image, image.width / 2, max(1, image.height / 2))
        }
        if (image.width != width) image = redraw(image, width, targetHeight)
        return image
    }

    private fun redraw(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(source, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
