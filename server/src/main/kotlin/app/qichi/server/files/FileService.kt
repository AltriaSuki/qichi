package app.qichi.server.files

import app.qichi.server.db.Files
import app.qichi.server.db.Messages
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.toUuidOrNull
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.shared.api.FileMeta
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWireOrNull
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.exists

/** 上传表单里读到的东西（文件内容已写到临时位置）。 */
class UploadForm(
    val kind: String?,
    val id: String?,
    val fileName: String?,
    val declaredType: String?,
    val staged: StagedFile?,
)

/** 下载时需要的信息。 */
class StoredFile(val meta: FileMeta, val path: Path)

private val log = LoggerFactory.getLogger(FileService::class.java)

class FileService(
    private val db: QichiDatabase,
    private val storage: FileStorage,
    private val clock: Clock,
) {
    /** 缩略图同时最多生成两张：大图解码很占内存 */
    private val thumbnailPermits = Semaphore(2)

    /** 目前开放上传的种类；review 在第 7 阶段开放 */
    private val uploadable = setOf(FileKind.Image, FileKind.File, FileKind.Avatar, FileKind.Hero, FileKind.Epub)

    /**
     * 上传大小上限：知道种类时按种类，还不知道（kind 字段排在文件后面）时先按最大的，
     * 收完再按种类复查。
     */
    suspend fun stage(source: ByteReadChannel, maxBytes: Long): StagedFile = storage.stage(source, maxBytes)

    fun discard(staged: StagedFile) = storage.discard(staged)

    fun limitFor(kind: String?): Long = if (kind != null && fromWireOrNull<FileKind>(kind)?.isImage == true) Limits.IMAGE_MAX_BYTES else Limits.FILE_MAX_BYTES

    /**
     * 新建文件记录并把临时文件归档。同 id 已存在且是自己在这个房间传的 → 返回已有的（created = false）；
     * 否则 409 conflict_id。无论成功与否，临时文件都会被清理。
     */
    suspend fun create(userId: UUID, roomId: UUID, form: UploadForm): Pair<FileMeta, Boolean> {
        val staged = form.staged
        try {
            val kind = form.kind?.let { fromWireOrNull<FileKind>(it) }
            val requestedId = form.id?.toUuidOrNull()
            val fileName = form.fileName?.let(::cleanFileName).orEmpty()
            validate {
                check(staged != null, "file", "缺少文件")
                check(kind != null && kind in uploadable, "kind", "只能是 image、file、avatar、hero、epub")
                check(form.id == null || requestedId != null, "id", "不是合法的 UUID")
                check(fileName.length in 1..255, "file", "文件名 1–255 个字")
            }
            staged!!
            kind!!
            val limit = if (kind.isImage) Limits.IMAGE_MAX_BYTES else Limits.FILE_MAX_BYTES
            if (staged.size > limit) payloadTooLarge(limit)

            val format = withContext(Dispatchers.IO) { Images.sniff(staged.temp) }
            val mimeType: String
            var size: Pair<Int, Int>? = null
            if (kind.isImage) {
                if (format == null) {
                    throw ApiException(ProblemCode.UnsupportedMediaType, "不支持这种图片", detail = "只支持 JPEG、PNG、WebP、HEIC、GIF")
                }
                mimeType = format.mimeType
                size = withContext(Dispatchers.IO) { Images.dimensions(staged.temp, format) }
            } else if (kind == FileKind.Epub) {
                if (!withContext(Dispatchers.IO) { Epub.looksValid(staged.temp) }) {
                    throw ApiException(ProblemCode.UnsupportedMediaType, "不是 EPUB 电子书", detail = "只支持 .epub 文件")
                }
                mimeType = Epub.MIME_TYPE
            } else {
                mimeType = format?.mimeType ?: cleanMimeType(form.declaredType) ?: "application/octet-stream"
            }

            val id = requestedId ?: UuidV7.generate()
            return db.tx {
                RoomRepository.lockRoom(roomId)
                if (!RoomRepository.isMember(roomId, userId)) notFound()
                val existing = Files.selectAll().where { Files.id eq id }.singleOrNull()
                if (existing != null) {
                    if (existing[Files.roomId] != roomId || existing[Files.uploadedBy] != userId) {
                        throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                    }
                    return@tx existing.toFileMeta() to false
                }
                val now = clock.instant()
                val date = now.atOffset(ZoneOffset.UTC)
                val path = "%s/%04d/%02d/%s".format(roomId, date.year, date.monthValue, id)
                Files.insert {
                    it[Files.id] = id
                    it[Files.roomId] = roomId
                    it[uploadedBy] = userId
                    it[Files.kind] = kind.wireName
                    it[Files.fileName] = fileName
                    it[Files.mimeType] = mimeType
                    it[sizeBytes] = staged.size
                    it[sha256] = staged.sha256
                    it[storagePath] = path
                    it[width] = size?.first
                    it[height] = size?.second
                    it[createdAt] = now
                }
                // 在事务里归档：移动失败则整条记录回滚，不会留下指向空文件的记录
                storage.commit(staged, path)
                Files.selectAll().where { Files.id eq id }.single().toFileMeta() to true
            }
        } finally {
            staged?.let(storage::discard)
        }
    }

    /** 下载：必须是文件所属房间的成员，否则 404（不暴露文件是否存在）。 */
    suspend fun open(userId: UUID, fileId: UUID): StoredFile {
        val (meta, relative) = db.tx(readOnly = true) {
            val row = Files.selectAll().where { Files.id eq fileId }.singleOrNull() ?: notFound()
            if (!RoomRepository.isMember(row[Files.roomId], userId)) notFound()
            row.toFileMeta() to row[Files.storagePath]
        }
        val path = storage.resolve(relative)
        if (!withContext(Dispatchers.IO) { path.exists() }) notFound()
        return StoredFile(meta, path)
    }

    /** 缩略图（JPEG），首次请求时生成并缓存在原文件旁边。只对图片有效；解不开的格式（HEIC）也返回 404。 */
    suspend fun thumbnail(userId: UUID, fileId: UUID, width: Int): Path {
        if (width !in Limits.THUMBNAIL_WIDTHS) {
            throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "w 只能是 200、400、800")
        }
        val file = open(userId, fileId)
        if (!file.meta.kind.isImage) notFound()
        val thumb = file.path.resolveSibling("${file.path.fileName}.w$width.jpg")
        if (withContext(Dispatchers.IO) { thumb.exists() }) return thumb
        val ok = thumbnailPermits.withPermit {
            withContext(Dispatchers.IO) {
                thumb.exists() || runCatching { Images.writeThumbnail(file.path, width, thumb) }.getOrDefault(false)
            }
        }
        if (!ok) notFound()
        return thumb
    }

    /** 去掉路径部分和控制字符；过长时保留扩展名截断。 */
    /**
     * 事务内调用：文件不再被任何消息引用时删掉它的记录，返回提交后要从磁盘删除的路径。
     * 用于撤回、彻底删除消息。
     */
    fun releaseIfUnused(fileId: UUID): String? {
        val stillUsed = Messages.select(Messages.id).where { Messages.fileId eq fileId }.limit(1).any() ||
            app.qichi.server.db.Books.select(app.qichi.server.db.Books.id).where { app.qichi.server.db.Books.fileId eq fileId }.limit(1).any()
        if (stillUsed) return null
        val path = Files.select(Files.storagePath).where { Files.id eq fileId }.singleOrNull()?.get(Files.storagePath) ?: return null
        Files.deleteWhere { Files.id eq fileId }
        return path
    }

    /** 事务提交后调用：删除磁盘上的文件和它的缩略图。失败只记日志，不影响请求。 */
    suspend fun deleteStored(paths: Collection<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (relative in paths) {
                runCatching {
                    val path = storage.resolve(relative)
                    path.parent?.toFile()?.listFiles { f -> f.name.startsWith("${path.fileName}.w") }?.forEach { it.delete() }
                    storage.delete(relative)
                }.onFailure { log.warn("删除文件失败：{}", relative, it) }
            }
        }
    }

    private fun cleanFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
        if (base.length <= 255) return base
        val ext = base.substringAfterLast('.', "").take(16)
        return if (ext.isEmpty()) base.take(255) else base.take(255 - ext.length - 1) + "." + ext
    }

    private fun cleanMimeType(raw: String?): String? {
        val type = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return type.takeIf { Regex("^[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*$").matches(it) }
    }
}

/** avatar、hero 也是图片 */
val FileKind.isImage: Boolean get() = this == FileKind.Image || this == FileKind.Avatar || this == FileKind.Hero
