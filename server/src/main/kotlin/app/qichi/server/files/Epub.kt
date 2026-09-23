package app.qichi.server.files

import java.nio.file.Path
import java.util.zip.ZipFile

/** EPUB 的最低限度检查：是 zip，且里面的 mimetype 写着 application/epub+zip（真正的解析在手机上由 Readium 做）。 */
object Epub {
    const val MIME_TYPE = "application/epub+zip"

    fun looksValid(file: Path): Boolean = runCatching {
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry("mimetype") ?: return false
            zip.getInputStream(entry).use { it.readNBytes(64).decodeToString().trim() == MIME_TYPE }
        }
    }.getOrDefault(false)
}
