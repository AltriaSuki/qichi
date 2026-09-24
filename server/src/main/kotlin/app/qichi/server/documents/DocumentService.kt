package app.qichi.server.documents

import app.qichi.shared.model.wireName
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.DocCategory
import app.qichi.shared.api.Patch
import app.qichi.shared.api.DocumentSearchHit
import app.qichi.server.db.DocumentVersions
import app.qichi.server.db.Documents
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.DocumentVersionInfo
import app.qichi.shared.api.DocumentVersionPage
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.UpdateDocumentRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.CjkText
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toDocument() = Document(
    id = this[Documents.id],
    roomId = this[Documents.roomId],
    seq = this[Documents.seq],
    createdAt = this[Documents.createdAt],
    updatedAt = this[Documents.updatedAt],
    deletedAt = this[Documents.deletedAt],
    deletedBy = this[Documents.deletedBy],
    title = this[Documents.title],
    createdBy = this[Documents.createdBy],
    latestVersion = this[Documents.latestVersion],
    latestAuthorId = this[Documents.latestAuthorId],
    charCount = this[Documents.charCount],
    pinned = this[Documents.pinned],
    category = this[Documents.category]?.let { fromWire<DocCategory>(it) },
)

private fun ResultRow.toVersionInfo() = DocumentVersionInfo(
    id = this[DocumentVersions.id],
    documentId = this[DocumentVersions.documentId],
    version = this[DocumentVersions.version],
    baseVersion = this[DocumentVersions.baseVersion],
    authorId = this[DocumentVersions.authorId],
    charCount = this[DocumentVersions.charCount],
    restoredFromVersion = this[DocumentVersions.restoredFromVersion],
    createdAt = this[DocumentVersions.createdAt],
)

private fun ResultRow.toVersion() = DocumentVersion(
    id = this[DocumentVersions.id],
    documentId = this[DocumentVersions.documentId],
    version = this[DocumentVersions.version],
    baseVersion = this[DocumentVersions.baseVersion],
    authorId = this[DocumentVersions.authorId],
    charCount = this[DocumentVersions.charCount],
    restoredFromVersion = this[DocumentVersions.restoredFromVersion],
    createdAt = this[DocumentVersions.createdAt],
    body = this[DocumentVersions.body],
)

private const val VERSION_PAGE_MAX = 100
private const val SEARCH_MAX = 50

/**
 * 共同写作（P5-01）：文稿是同步实体（标题、最新版本号、作者、字数）；每次保存插入一个不可变版本。
 * 保存必须基于最新版本，否则 409 conflict_version，交给客户端「重基线」，服务端从不合并正文。
 */
class DocumentService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun document(id: UUID): Document? = Documents.selectAll().where { Documents.id eq id }.singleOrNull()?.toDocument()

    /** 本房间里、没被删除的文稿；否则 404。 */
    private fun liveDocument(roomId: UUID, id: UUID): Document =
        document(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()

    private fun checkTitle(raw: String): String {
        val title = raw.trim()
        validate { check(title.length in Limits.DOCUMENT_TITLE_LENGTH, "title", "标题 1–${Limits.DOCUMENT_TITLE_LENGTH.last} 字") }
        return title
    }

    suspend fun list(userId: UUID, roomId: UUID): List<Document> = db.tx {
        rooms.requireMember(roomId, userId)
        Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }
            .orderBy(Documents.updatedAt, SortOrder.DESC).map { it.toDocument() }
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateDocumentRequest): Pair<Document, Boolean> {
        val title = checkTitle(req.title)
        return db.tx {
            rooms.requireMember(roomId, userId)
            writes.create(this, roomId, userId, EntityType.Document, req.id, Documents, ::document) {
                it[Documents.title] = title
                it[Documents.createdBy] = userId
                it[Documents.latestVersion] = 0
                it[Documents.charCount] = 0
            }
        }
    }

    /** 改标题、置顶、分类（P9-06）：没发的字段不改；都不进版本。 */
    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateDocumentRequest): Document {
        validate { check(req.title.isPresent || req.pinned.isPresent || req.category.isPresent, "title", "没有要改的") }
        val title = (req.title as? Patch.Value)?.value?.let(::checkTitle)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = liveDocument(roomId, id)
            val pinned = req.pinned.orNull() ?: current.pinned
            val category = if (req.category.isPresent) req.category.orNull() else current.category
            if ((title ?: current.title) != current.title || pinned != current.pinned || category != current.category) {
                writes.update(this, roomId, userId, EntityType.Document, id, Documents) {
                    if (title != null) it[Documents.title] = title
                    it[Documents.pinned] = pinned
                    it[Documents.category] = category?.wireName
                }
            }
            document(id)!!
        }
    }

    /**
     * 在标题和最新版本正文里搜（P9-06）：不含回收站里的，最近更新的在前，最多 [SEARCH_MAX] 条。
     * 命中正文时给出命中处前后的一小段；只命中标题时给正文开头。
     */
    suspend fun search(userId: UUID, roomId: UUID, rawQuery: String): List<DocumentSearchHit> {
        val q = rawQuery.trim()
        validate { check(q.length in 1..100, "q", "搜索词 1–100 个字") }
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val docs = Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }
                .orderBy(Documents.updatedAt, SortOrder.DESC).map { it.toDocument() }
            docs.asSequence().mapNotNull { d ->
                val body = if (d.latestVersion == 0) "" else DocumentVersions.select(DocumentVersions.body)
                    .where { (DocumentVersions.documentId eq d.id) and (DocumentVersions.version eq d.latestVersion) }.single()[DocumentVersions.body]
                val at = body.indexOf(q, ignoreCase = true)
                when {
                    at >= 0 -> DocumentSearchHit(d.id, snippet(body, at, q.length))
                    d.title.contains(q, ignoreCase = true) -> DocumentSearchHit(d.id, snippet(body, 0, 0))
                    else -> null
                }
            }.take(SEARCH_MAX).toList()
        }
    }

    /** 命中处前 20 字、后 40 字，换行压成空格。 */
    private fun snippet(body: String, at: Int, len: Int): String {
        val from = (at - 20).coerceAtLeast(0)
        val to = (at + len + 40).coerceAtMost(body.length)
        return (if (from > 0) "…" else "") + body.substring(from, to).replace(Regex("\\s+"), " ").trim() + (if (to < body.length) "…" else "")
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Document = db.tx {
        rooms.requireMember(roomId, userId)
        val current = document(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Document, id, Documents)
        document(id)!!
    }

    /** 版本号从大到小；游标是上一页最后一个版本号。 */
    suspend fun versions(userId: UUID, roomId: UUID, id: UUID, cursor: String?, limit: Int): DocumentVersionPage {
        val before = cursor?.let { it.toIntOrNull() ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "cursor 不合法") }
        val take = limit.coerceIn(1, VERSION_PAGE_MAX)
        return db.tx {
            rooms.requireMember(roomId, userId)
            liveDocument(roomId, id)
            val query = DocumentVersions.select(
                DocumentVersions.id, DocumentVersions.documentId, DocumentVersions.version, DocumentVersions.baseVersion,
                DocumentVersions.authorId, DocumentVersions.charCount, DocumentVersions.restoredFromVersion, DocumentVersions.createdAt,
            ).where { DocumentVersions.documentId eq id }
            if (before != null) query.andWhere { DocumentVersions.version less before }
            val rows = query.orderBy(DocumentVersions.version, SortOrder.DESC).limit(take + 1).map { it.toVersionInfo() }
            val page = rows.take(take)
            DocumentVersionPage(page, if (rows.size > take) page.last().version.toString() else null)
        }
    }

    suspend fun version(userId: UUID, roomId: UUID, id: UUID, version: Int): DocumentVersion = db.tx {
        rooms.requireMember(roomId, userId)
        liveDocument(roomId, id)
        DocumentVersions.selectAll().where { (DocumentVersions.documentId eq id) and (DocumentVersions.version eq version) }
            .singleOrNull()?.toVersion() ?: notFound()
    }

    suspend fun save(userId: UUID, roomId: UUID, id: UUID, req: SaveDocumentVersionRequest): Pair<DocumentVersion, Boolean> {
        validate {
            check(req.body.length <= Limits.DOCUMENT_BODY_MAX, "body", "正文最多 ${Limits.DOCUMENT_BODY_MAX} 个字符")
            check(req.baseVersion >= 0, "baseVersion", "基线版本不能小于 0")
            check(req.restoredFromVersion == null || req.restoredFromVersion!! in 1..req.baseVersion, "restoredFromVersion", "只能另存已有的版本")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            // 先锁住房间：同一文稿的两次保存串行，不会拿到同一个版本号
            app.qichi.server.rooms.RoomRepository.lockRoom(roomId)
            val existing = DocumentVersions.selectAll().where { DocumentVersions.id eq req.id }.singleOrNull()?.toVersion()
            if (existing != null) {
                // 同一个 id 重试：返回已经保存的版本；换了文稿或作者说明 id 撞了
                if (existing.documentId != id || existing.authorId != userId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                document(id)?.takeIf { it.roomId == roomId } ?: notFound()
                return@tx existing to false
            }
            val doc = liveDocument(roomId, id)
            if (req.baseVersion != doc.latestVersion) {
                throw ApiException(
                    ProblemCode.ConflictVersion,
                    "文稿已有更新的版本",
                    detail = "当前最新版本是 v${doc.latestVersion}，你基于 v${req.baseVersion} 保存",
                    latestVersion = doc.latestVersion,
                )
            }
            val next = doc.latestVersion + 1
            val count = CjkText.charCount(req.body)
            val now = writes.now()
            DocumentVersions.insert {
                it[DocumentVersions.id] = req.id
                it[documentId] = id
                it[version] = next
                it[baseVersion] = req.baseVersion
                it[authorId] = userId
                it[body] = req.body
                it[charCount] = count
                it[restoredFromVersion] = req.restoredFromVersion
                it[createdAt] = now
            }
            writes.update(this, roomId, userId, EntityType.Document, id, Documents) {
                it[Documents.latestVersion] = next
                it[Documents.latestAuthorId] = userId
                it[Documents.charCount] = count
            }
            DocumentVersions.selectAll().where { DocumentVersions.id eq req.id }.single().toVersion() to true
        }
    }
}
