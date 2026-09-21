package app.qichi.server.files

import app.qichi.server.plugins.ApiException
import app.qichi.shared.model.ProblemCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/** 已写到临时位置、还没归档的上传内容。 */
class StagedFile(val temp: Path, val size: Long, val sha256: String)

/**
 * 文件存放（docs/02-architecture.md「文件存储」）。路径一律相对根目录，
 * 按 `{roomId}/{yyyy}/{mm}/{fileId}` 存放；下载必须经过服务端鉴权，不暴露静态目录。
 */
interface FileStorage {
    /** 把 [source] 写到临时文件，边写边算 sha256；超过 [maxBytes] 抛 413。 */
    suspend fun stage(source: ByteReadChannel, maxBytes: Long): StagedFile

    /** 把临时文件移到 [relativePath]（已存在则覆盖）。 */
    fun commit(staged: StagedFile, relativePath: String)

    /** 丢弃临时文件（已经 commit 的不受影响）。 */
    fun discard(staged: StagedFile)

    fun resolve(relativePath: String): Path

    fun delete(relativePath: String)
}

fun payloadTooLarge(maxBytes: Long): Nothing = throw ApiException(
    ProblemCode.PayloadTooLarge,
    "文件太大",
    detail = "不能超过 ${maxBytes / 1024 / 1024}MB",
)

/** 写本地目录（FILES_DIR）。临时文件放在同一磁盘的 `.tmp/` 下，归档时原子移动。 */
class LocalFileStorage(root: Path) : FileStorage {
    private val root: Path = root.toAbsolutePath().normalize()
    private val tmpDir: Path = this.root.resolve(".tmp")

    init {
        Files.createDirectories(tmpDir)
    }

    override suspend fun stage(source: ByteReadChannel, maxBytes: Long): StagedFile {
        val temp = withContext(Dispatchers.IO) { Files.createTempFile(tmpDir, "upload-", ".part") }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            withContext(Dispatchers.IO) {
                Files.newOutputStream(temp).use { out: OutputStream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.readAvailable(buffer, 0, buffer.size)
                        if (n == -1) break
                        total += n
                        if (total > maxBytes) payloadTooLarge(maxBytes)
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            Files.deleteIfExists(temp)
            throw e
        }
        return StagedFile(temp, total, HexFormat.of().formatHex(digest.digest()))
    }

    override fun commit(staged: StagedFile, relativePath: String) {
        val target = resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.move(staged.temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun discard(staged: StagedFile) {
        Files.deleteIfExists(staged.temp)
    }

    override fun resolve(relativePath: String): Path {
        val path = root.resolve(relativePath).normalize()
        require(path.startsWith(root) && path != root) { "路径越界：$relativePath" }
        return path
    }

    override fun delete(relativePath: String) {
        Files.deleteIfExists(resolve(relativePath))
    }
}
