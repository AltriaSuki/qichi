package app.qichi.server.files

import app.qichi.server.plugins.ApiException
import app.qichi.shared.model.ProblemCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** 已写到临时位置、还没归档的上传内容。 */
class StagedFile(val temp: Path, val size: Long, val sha256: String)

/** 磁盘上的一个文件：相对根目录的路径（和 files.storage_path 同样的写法）、最后修改时间。 */
class StoredEntry(val path: String, val modified: Instant)

/**
 * 文件存放（docs/02-architecture.md「文件存储」）。路径一律相对根目录，
 * 按 `{roomId}/{yyyy}/{mm}/{fileId}` 存放；下载必须经过服务端鉴权，不暴露静态目录。
 */
interface FileStorage {
    /** 把 [source] 写到临时文件，边写边算 sha256；超过 [maxBytes] 抛 413。 */
    suspend fun stage(source: ByteReadChannel, maxBytes: Long): StagedFile

    /** 把一段内存里的内容写成临时文件（服务端自己生成的文件用）。 */
    fun stageBytes(bytes: ByteArray): StagedFile

    /** 把临时文件移到 [relativePath]（已存在则覆盖）。 */
    fun commit(staged: StagedFile, relativePath: String)

    /** 丢弃临时文件（已经 commit 的不受影响）。 */
    fun discard(staged: StagedFile)

    fun resolve(relativePath: String): Path

    fun delete(relativePath: String)

    // ── 回收没有记录的文件（每天的清理用） ──

    /** 按房间存放的顶层目录（名字是房间 id）。临时目录、不是按房间存放的目录（如 app-releases）不在里面。 */
    fun roomDirs(): List<UUID>

    /** 房间目录下的所有文件（含缩略图）。 */
    fun filesIn(roomId: UUID): List<StoredEntry>

    /** 删掉房间目录下已经空了的月、年目录和房间目录本身。 */
    fun pruneEmpty(roomId: UUID)

    /** 删掉临时目录里最后修改早于 [before] 的文件（上传到一半进程没了留下的），返回删了几个。 */
    fun cleanTemp(before: Instant): Int
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

    override fun stageBytes(bytes: ByteArray): StagedFile {
        val temp = Files.createTempFile(tmpDir, "generated-", ".part")
        Files.write(temp, bytes)
        return StagedFile(temp, bytes.size.toLong(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    override fun commit(staged: StagedFile, relativePath: String) {
        val target = resolve(relativePath)
        // 空目录会被每天的清理删掉：刚建好就被删了的话，再建一次
        for (attempt in 1..2) {
            Files.createDirectories(target.parent)
            try {
                Files.move(staged.temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: NoSuchFileException) {
                if (attempt == 2 || !Files.exists(staged.temp)) throw e
            }
        }
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

    override fun roomDirs(): List<UUID> = Files.list(root).use { entries ->
        entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList().mapNotNull { dir ->
            val name = dir.fileName.toString()
            runCatching { UUID.fromString(name) }.getOrNull()?.takeIf { it.toString() == name }
        }
    }

    override fun filesIn(roomId: UUID): List<StoredEntry> {
        val dir = root.resolve(roomId.toString())
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList().map {
                StoredEntry(root.relativize(it).joinToString("/"), Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toInstant())
            }
        }
    }

    override fun pruneEmpty(roomId: UUID) {
        val dir = root.resolve(roomId.toString())
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        // 从最深的开始：月、年、房间目录
        val dirs = Files.walk(dir).use { paths -> paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
        for (d in dirs.sortedByDescending { it.nameCount }) {
            val empty = Files.newDirectoryStream(d).use { !it.iterator().hasNext() }
            // 刚好有文件放进来（上传）就不删了
            if (empty) runCatching { Files.deleteIfExists(d) }
        }
    }

    override fun cleanTemp(before: Instant): Int = Files.list(tmpDir).use { entries ->
        entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList().count { path ->
            Files.getLastModifiedTime(path).toInstant().isBefore(before) && Files.deleteIfExists(path)
        }
    }
}
