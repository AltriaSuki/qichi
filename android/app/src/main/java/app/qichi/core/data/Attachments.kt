package app.qichi.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.qichi.shared.model.FileKind
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 准备好、可以上传的附件。[open] 每次调用都返回新的输入流（重试上传时重新读）。 */
class PreparedAttachment(
    val kind: FileKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** 本机预览用（上传中的气泡） */
    val previewUri: Uri,
    val width: Int?,
    val height: Int?,
    val open: () -> InputStream,
)

/** 附件不能发送的原因（给人看的一句话）。 */
class AttachmentException(message: String) : Exception(message)

/**
 * 把用户选的图片、文件变成可以上传的附件。
 * 图片：GIF 原样；较小的 PNG / WebP 原样；其余转成长边不超过 2560 的 JPEG（同时按 EXIF 摆正方向）。
 */
class AttachmentPreparer(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val dir: File get() = File(context.cacheDir, "outgoing").apply { mkdirs() }

    suspend fun image(uri: Uri): PreparedAttachment = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val (name, size) = nameAndSize(uri)
        val bounds = decodeBounds(uri)
        val keepOriginal = mime == "image/gif" ||
            (mime in setOf("image/png", "image/webp") && size in 1..KEEP_ORIGINAL_BYTES && bounds != null && max(bounds.first, bounds.second) <= MAX_EDGE)
        if (keepOriginal) {
            if (size > Limits.IMAGE_MAX_BYTES) throw AttachmentException("图片太大（不能超过 20MB）")
            return@withContext PreparedAttachment(
                FileKind.Image, name ?: "image", mime, size, uri, bounds?.first, bounds?.second,
            ) { resolver.openInputStream(uri) ?: throw AttachmentException("读不到这张图片") }
        }
        val bitmap = decodeScaled(uri) ?: throw AttachmentException("这张图片打不开")
        val out = File(dir, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val result = PreparedAttachment(
            FileKind.Image, jpegName(name), "image/jpeg", out.length(), Uri.fromFile(out), bitmap.width, bitmap.height,
        ) { out.inputStream() }
        bitmap.recycle()
        if (result.sizeBytes > Limits.IMAGE_MAX_BYTES) throw AttachmentException("图片太大（不能超过 20MB）")
        result
    }

    suspend fun file(uri: Uri): PreparedAttachment = withContext(Dispatchers.IO) {
        val (name, size) = nameAndSize(uri)
        if (size > Limits.FILE_MAX_BYTES) throw AttachmentException("文件太大（不能超过 100MB）")
        if (size < 0) throw AttachmentException("读不到这个文件")
        PreparedAttachment(
            FileKind.File, name ?: "file", resolver.getType(uri) ?: "application/octet-stream", size, uri, null, null,
        ) { resolver.openInputStream(uri) ?: throw AttachmentException("读不到这个文件") }
    }

    /** 清理发出去的临时图片（上传成功或放弃后）。 */
    fun cleanup(attachment: PreparedAttachment) {
        attachment.previewUri.path?.let { path -> File(path).takeIf { it.parentFile == dir }?.delete() }
    }

    private fun nameAndSize(uri: Uri): Pair<String?, Long> {
        if (uri.scheme == "file") return uri.lastPathSegment to (uri.path?.let { File(it).length() } ?: -1)
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong) ?: -1
                return name to size
            }
        }
        return null to -1
    }

    private fun decodeBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会按 EXIF 自动摆正
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = decodeBounds(uri) ?: return null
        var sample = 1
        while (max(bounds.first, bounds.second) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val (w, h) = targetSize(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun exifRotation(stream: InputStream): Int = when (
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    companion object {
        const val MAX_EDGE = 2560
        const val JPEG_QUALITY = 85
        const val KEEP_ORIGINAL_BYTES = 2L * 1024 * 1024

        /** 长边不超过 [MAX_EDGE]，按比例缩小（不放大）。 */
        fun targetSize(width: Int, height: Int): Pair<Int, Int> {
            val longest = max(width, height)
            if (longest <= MAX_EDGE) return width to height
            val scale = MAX_EDGE.toDouble() / longest
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }

        /** 转成 JPEG 后的文件名：换掉扩展名。 */
        fun jpegName(original: String?): String {
            val base = original?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "image"
            return "$base.jpg"
        }
    }
}
