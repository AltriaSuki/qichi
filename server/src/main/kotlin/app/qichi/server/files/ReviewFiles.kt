package app.qichi.server.files

import app.qichi.shared.model.ReviewFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 审稿原文件：认出是哪一类（先看文件头 / zip 里的内容，老格式和纯文本再看扩展名），决定预览怎么生成、能按什么批注。
 * 认不出的返回 null（上传时 415）。
 */
object ReviewFiles {
    data class Kind(val format: ReviewFormat, val mimeType: String)

    private val PDF = Kind(ReviewFormat.Pdf, "application/pdf")

    fun sniff(file: Path, fileName: String): Kind? {
        val head = Files.newInputStream(file).use { it.readNBytes(8) }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            head.startsWith("%PDF-".toByteArray()) -> PDF
            head.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) -> zipKind(file)
            // OLE2：老的 .doc / .xls / .ppt，只能看扩展名
            head.startsWith(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) -> when (ext) {
                "doc" -> Kind(ReviewFormat.Text, "application/msword")
                "xls" -> Kind(ReviewFormat.Sheet, "application/vnd.ms-excel")
                "ppt" -> Kind(ReviewFormat.Slides, "application/vnd.ms-powerpoint")
                else -> null
            }
            head.startsWith("{\\rtf".toByteArray()) -> Kind(ReviewFormat.Text, "application/rtf")
            ext in setOf("txt", "md", "csv") && looksLikeText(file) ->
                if (ext == "csv") Kind(ReviewFormat.Sheet, "text/csv") else Kind(ReviewFormat.Text, "text/plain")
            else -> null
        }
    }

    private fun zipKind(file: Path): Kind? = runCatching {
        ZipFile(file.toFile()).use { zip ->
            zip.getEntry("mimetype")?.let { entry ->
                val type = zip.getInputStream(entry).use { it.readNBytes(128).decodeToString().trim() }
                return when (type) {
                    "application/vnd.oasis.opendocument.text" -> Kind(ReviewFormat.Text, type)
                    "application/vnd.oasis.opendocument.spreadsheet" -> Kind(ReviewFormat.Sheet, type)
                    "application/vnd.oasis.opendocument.presentation" -> Kind(ReviewFormat.Slides, type)
                    else -> null
                }
            }
            when {
                zip.getEntry("word/document.xml") != null ->
                    Kind(ReviewFormat.Text, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                zip.getEntry("xl/workbook.xml") != null ->
                    Kind(ReviewFormat.Sheet, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                zip.getEntry("ppt/presentation.xml") != null ->
                    Kind(ReviewFormat.Slides, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                else -> null
            }
        }
    }.getOrNull()

    /** 前 8KB 里没有 0 字节、能按 UTF-8 解开（允许在末尾截断一个字）。 */
    private fun looksLikeText(file: Path): Boolean {
        val bytes = Files.newInputStream(file).use { it.readNBytes(8192) }
        if (bytes.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, trimPartial(bytes))) }.isSuccess
    }

    private fun trimPartial(bytes: ByteArray): Int {
        var end = bytes.size
        var back = 0
        while (back < 4 && end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0x80) back++
        if (end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0xC0) end -= back + 1
        return end
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
