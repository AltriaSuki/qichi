package app.qichi.server.reading

import app.qichi.server.db.Books
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Files
import app.qichi.server.db.Highlights
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.ReadingProgressTable
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.Book
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Patch
import app.qichi.shared.api.PutReadingProgressRequest
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.UpdateBookRequest
import app.qichi.shared.api.UpdateHighlightRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** 书与文件大小一起查（书架上显示、离线缓存估算用）。 */
fun bookQuery() = Books.join(Files, JoinType.INNER, Books.fileId, Files.id).selectAll()

fun ResultRow.toBook() = Book(
    id = this[Books.id], roomId = this[Books.roomId], seq = this[Books.seq],
    createdAt = this[Books.createdAt], updatedAt = this[Books.updatedAt],
    deletedAt = this[Books.deletedAt], deletedBy = this[Books.deletedBy],
    title = this[Books.title], author = this[Books.author], fileId = this[Books.fileId], sizeBytes = this[Files.sizeBytes],
    addedBy = this[Books.addedBy], planTargetDate = this[Books.planTargetDate], planNote = this[Books.planNote],
)

fun ResultRow.toReadingProgress() = ReadingProgress(
    id = this[ReadingProgressTable.id], roomId = this[ReadingProgressTable.roomId], seq = this[ReadingProgressTable.seq],
    createdAt = this[ReadingProgressTable.createdAt], updatedAt = this[ReadingProgressTable.updatedAt],
    deletedAt = this[ReadingProgressTable.deletedAt], deletedBy = this[ReadingProgressTable.deletedBy],
    bookId = this[ReadingProgressTable.bookId], userId = this[ReadingProgressTable.userId],
    locator = this[ReadingProgressTable.locator], progress = this[ReadingProgressTable.progress],
)

fun ResultRow.toHighlight() = Highlight(
    id = this[Highlights.id], roomId = this[Highlights.roomId], seq = this[Highlights.seq],
    createdAt = this[Highlights.createdAt], updatedAt = this[Highlights.updatedAt],
    deletedAt = this[Highlights.deletedAt], deletedBy = this[Highlights.deletedBy],
    bookId = this[Highlights.bookId], userId = this[Highlights.userId], kind = fromWire<HighlightKind>(this[Highlights.kind]),
    locator = this[Highlights.locator], text = this[Highlights.text], note = this[Highlights.note], shared = this[Highlights.shared],
)

/** 对方的标记只有共享了才看得到（同步、快照都按这个过滤）。 */
fun Highlight.visibleTo(userId: UUID): Boolean = this.userId == userId || shared

/**
 * 阅读（P6-04）：书架、各自进度（每人每本一条）、书签 / 标注 / 摘录（只能改删自己的，共享后对方可见）、共读计划。
 * 书两位成员都能改、都能拿下书架（进回收站）。
 */
class ReadingService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun book(id: UUID): Book? = bookQuery().where { Books.id eq id }.singleOrNull()?.toBook()
    private fun liveBook(roomId: UUID, id: UUID) = book(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
    private fun progress(id: UUID) = ReadingProgressTable.selectAll().where { ReadingProgressTable.id eq id }.singleOrNull()?.toReadingProgress()
    private fun highlight(id: UUID) = Highlights.selectAll().where { Highlights.id eq id }.singleOrNull()?.toHighlight()

    private fun cleanTitle(raw: String): String {
        val t = raw.trim()
        validate { check(t.length in Limits.BOOK_TITLE_LENGTH, "title", "书名 1–${Limits.BOOK_TITLE_LENGTH.last} 字") }
        return t
    }

    private fun cleanOptional(raw: String?, max: Int, field: String): String? {
        val t = raw?.trim()?.ifEmpty { null }
        validate { check((t?.length ?: 0) <= max, field, "最多 $max 字") }
        return t
    }

    // ── 书 ──

    suspend fun books(userId: UUID, roomId: UUID): List<Book> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        bookQuery().where { (Books.roomId eq roomId) and Books.deletedAt.isNull() }.orderBy(Books.createdAt, SortOrder.DESC).map { it.toBook() }
    }

    suspend fun createBook(userId: UUID, roomId: UUID, req: CreateBookRequest): Pair<Book, Boolean> {
        val title = cleanTitle(req.title)
        val author = cleanOptional(req.author, Limits.BOOK_AUTHOR_MAX, "author")
        return db.tx {
            rooms.requireMember(roomId, userId)
            val isEpub = Files.select(Files.id).where { (Files.id eq req.fileId) and (Files.roomId eq roomId) and (Files.kind eq FileKind.Epub.wireName) }.any()
            validate { check(isEpub, "fileId", "要先把 EPUB 传上来") }
            writes.create(this, roomId, userId, EntityType.Book, req.id, Books, ::book) {
                it[Books.title] = title
                it[Books.author] = author
                it[Books.fileId] = req.fileId
                it[Books.addedBy] = userId
            }
        }
    }

    suspend fun updateBook(userId: UUID, roomId: UUID, id: UUID, req: UpdateBookRequest): Book {
        val title = (req.title as? Patch.Value)?.value?.let(::cleanTitle)
        val author = (req.author as? Patch.Value)?.let { cleanOptional(it.value, Limits.BOOK_AUTHOR_MAX, "author") }
        val note = (req.planNote as? Patch.Value)?.let { cleanOptional(it.value, Limits.BOOK_PLAN_NOTE_MAX, "planNote") }
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = liveBook(roomId, id)
            val target = (req.planTargetDate as? Patch.Value)?.value
            val changes = listOf(
                title != null && title != current.title,
                req.author is Patch.Value && author != current.author,
                req.planTargetDate is Patch.Value && target != current.planTargetDate,
                req.planNote is Patch.Value && note != current.planNote,
            )
            if (changes.any { it }) {
                writes.update(this, roomId, userId, EntityType.Book, id, Books) {
                    if (changes[0]) it[Books.title] = title!!
                    if (changes[1]) it[Books.author] = author
                    if (changes[2]) it[Books.planTargetDate] = target
                    if (changes[3]) it[Books.planNote] = note
                }
            }
            book(id)!!
        }
    }

    suspend fun deleteBook(userId: UUID, roomId: UUID, id: UUID): Book = db.tx {
        rooms.requireMember(roomId, userId)
        val current = book(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Book, id, Books)
        book(id)!!
    }

    // ── 进度 ──

    suspend fun putProgress(userId: UUID, roomId: UUID, bookId: UUID, req: PutReadingProgressRequest): ReadingProgress {
        validate {
            check(req.locator.length in 1..Limits.LOCATOR_MAX, "locator", "定位信息不对")
            check(req.progress in 0.0..1.0, "progress", "进度在 0 到 1 之间")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            liveBook(roomId, bookId)
            val existing = ReadingProgressTable.selectAll()
                .where { (ReadingProgressTable.bookId eq bookId) and (ReadingProgressTable.userId eq userId) }.singleOrNull()?.toReadingProgress()
            if (existing != null) {
                if (existing.locator != req.locator || existing.progress != req.progress) {
                    writes.update(this, roomId, userId, EntityType.ReadingProgress, existing.id, ReadingProgressTable) {
                        it[ReadingProgressTable.locator] = req.locator
                        it[ReadingProgressTable.progress] = req.progress
                    }
                }
                progress(existing.id)!!
            } else {
                // 这个 id 已被别的进度用了（不是自己这本书的）：id 冲突
                progress(req.id)?.let { throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用") }
                writes.create(this, roomId, userId, EntityType.ReadingProgress, req.id, ReadingProgressTable, ::progress) {
                    it[ReadingProgressTable.bookId] = bookId
                    it[ReadingProgressTable.userId] = userId
                    it[ReadingProgressTable.locator] = req.locator
                    it[ReadingProgressTable.progress] = req.progress
                }.first
            }
        }
    }

    // ── 书签、标注、摘录 ──

    suspend fun createHighlight(userId: UUID, roomId: UUID, bookId: UUID, req: CreateHighlightRequest): Pair<Highlight, Boolean> {
        val note = cleanOptional(req.note, Limits.HIGHLIGHT_NOTE_MAX, "note")
        validate {
            check(req.locator.length in 1..Limits.LOCATOR_MAX, "locator", "定位信息不对")
            check(req.text.length <= Limits.HIGHLIGHT_TEXT_MAX, "text", "选中的文字太长了")
            check(req.kind == HighlightKind.Bookmark || req.text.isNotBlank(), "text", "标注和摘录要有选中的文字")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            liveBook(roomId, bookId)
            val result = writes.create(this, roomId, userId, EntityType.Highlight, req.id, Highlights, ::highlight) {
                it[Highlights.bookId] = bookId
                it[Highlights.userId] = userId
                it[Highlights.kind] = req.kind.wireName
                it[Highlights.locator] = req.locator
                it[Highlights.text] = req.text
                it[Highlights.note] = note
                it[Highlights.shared] = req.shared
            }
            if (!result.second && (result.first.userId != userId || result.first.bookId != bookId)) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            result
        }
    }

    private fun ownHighlight(roomId: UUID, bookId: UUID, id: UUID, userId: UUID): Highlight {
        val h = highlight(id)?.takeIf { it.roomId == roomId && it.bookId == bookId && it.deletedAt == null && it.visibleTo(userId) } ?: notFound()
        if (h.userId != userId) forbidden("只能改自己的标记")
        return h
    }

    suspend fun updateHighlight(userId: UUID, roomId: UUID, bookId: UUID, id: UUID, req: UpdateHighlightRequest): Highlight {
        val note = (req.note as? Patch.Value)?.let { cleanOptional(it.value, Limits.HIGHLIGHT_NOTE_MAX, "note") }
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = ownHighlight(roomId, bookId, id, userId)
            val shared = (req.shared as? Patch.Value)?.value
            val noteChanged = req.note is Patch.Value && note != current.note
            val sharedChanged = shared != null && shared != current.shared
            if (noteChanged || sharedChanged) {
                writes.update(this, roomId, userId, EntityType.Highlight, id, Highlights) {
                    if (noteChanged) it[Highlights.note] = note
                    if (sharedChanged) it[Highlights.shared] = shared!!
                }
            }
            highlight(id)!!
        }
    }

    suspend fun deleteHighlight(userId: UUID, roomId: UUID, bookId: UUID, id: UUID): Highlight = db.tx {
        rooms.requireMember(roomId, userId)
        ownHighlight(roomId, bookId, id, userId)
        writes.softDelete(this, roomId, userId, EntityType.Highlight, id, Highlights)
        highlight(id)!!
    }
}
