package app.qichi.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.reading.EpubException
import app.qichi.core.reading.EpubOpener
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
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
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * 阅读：书、进度、书签 / 标注 / 摘录都是同步实体，先写本机再经发件箱发出（离线也能记）。
 * 加书要先上传 EPUB（需要联网）；书的正文在 [BookCache] 里，不进同步。
 */
class ReadingRepository(
    private val context: Context,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val files: FileRepository,
    private val cache: BookCache,
    private val epubs: EpubOpener,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    private inline fun <reified T : app.qichi.shared.api.SyncEntity> observe(roomId: UUID, type: EntityType): Flow<List<Local<T>>> =
        db.entities().observeByType(roomId.toString(), type.wireName).map { rows -> rows.map { LocalStore.toLocal<T>(it) } }

    fun observeBooks(roomId: UUID): Flow<List<Local<Book>>> =
        observe<Book>(roomId, EntityType.Book).map { list -> list.sortedByDescending { it.value.createdAt } }

    fun observeProgress(roomId: UUID): Flow<List<ReadingProgress>> = observe<ReadingProgress>(roomId, EntityType.ReadingProgress).map { l -> l.map { it.value } }

    fun observeHighlights(roomId: UUID): Flow<List<Local<Highlight>>> = observe(roomId, EntityType.Highlight)

    // ── 书 ──

    /**
     * 把选中的 EPUB 放上书架：读出书名作者 → 上传（需要联网）→ 建书 → 放进本机缓存。
     * @throws EpubException 不是能打开的 EPUB
     */
    suspend fun addBook(roomId: UUID, uri: Uri, onProgress: (Float) -> Unit = {}): Book {
        val resolver = context.contentResolver
        val name = withContext(Dispatchers.IO) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } ?: "book.epub"
        val temp = File(context.cacheDir, "incoming-${UUID.randomUUID()}.epub")
        try {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: throw EpubException("读不到这个文件")
            }
            if (temp.length() > Limits.FILE_MAX_BYTES) throw EpubException("书太大了（不能超过 100MB）")
            val info = epubs.info(temp)
            val fileId = UuidV7.generate()
            val attachment = PreparedAttachment(FileKind.Epub, name, "application/epub+zip", temp.length(), uri, null, null) { temp.inputStream() }
            val meta = files.upload(roomId, fileId, attachment, FileKind.Epub, onProgress)
            cache.put(meta.id, temp)
            val title = (info.title ?: name.removeSuffix(".epub")).take(Limits.BOOK_TITLE_LENGTH.last)
            val author = info.author?.take(Limits.BOOK_AUTHOR_MAX)
            val now = clock.instant()
            val book = Book(UuidV7.generate(), roomId, 0, now, now, null, null, title, author, meta.id, meta.sizeBytes, me, null, null)
            store.writeLocal(roomId, book, OutboxOp.post("rooms/$roomId/books", CreateBookRequest(book.id, meta.id, title, author)))
            scheduler.kickOutbox()
            return book
        } finally {
            withContext(Dispatchers.IO) { temp.delete() }
        }
    }

    suspend fun updatePlan(book: Book, target: LocalDate?, rawNote: String?) {
        val note = rawNote?.trim()?.take(Limits.BOOK_PLAN_NOTE_MAX)?.ifEmpty { null }
        if (target == book.planTargetDate && note == book.planNote) return
        store.writeLocal(book.roomId, book.copy(planTargetDate = target, planNote = note, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${book.roomId}/books/${book.id}", UpdateBookRequest(planTargetDate = Patch.of(target), planNote = Patch.of(note))))
        scheduler.kickOutbox()
    }

    suspend fun delete(book: Book) {
        store.writeLocal(book.roomId, book.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${book.roomId}/books/${book.id}"))
        scheduler.kickOutbox()
    }

    // ── 进度 ──

    /** 保存我的进度：本机只留一条，发件箱里同一本书只留最新的一次。 */
    suspend fun saveProgress(book: Book, locatorJson: String, progress: Double) {
        if (locatorJson.length > Limits.LOCATOR_MAX) return
        val p = progress.coerceIn(0.0, 1.0)
        val now = clock.instant()
        db.transaction {
            val existing = db.entities().byParent(EntityType.ReadingProgress.wireName, book.id.toString())
                .firstOrNull { it.ownerId == me.toString() }?.let { LocalStore.toLocal<ReadingProgress>(it).value }
            val next = existing?.copy(locator = locatorJson, progress = p, updatedAt = now)
                ?: ReadingProgress(UuidV7.generate(), book.roomId, 0, now, now, null, null, book.id, me, locatorJson, p)
            db.outbox().deleteAllFor(EntityType.ReadingProgress.wireName, next.id.toString())
            store.writeLocal(book.roomId, next,
                OutboxOp.put("rooms/${book.roomId}/books/${book.id}/progress", PutReadingProgressRequest(next.id, locatorJson, p), kind = OutboxOp.KIND_READING_PROGRESS))
        }
        scheduler.kickOutbox()
    }

    // ── 书签、标注、摘录 ──

    suspend fun addHighlight(book: Book, kind: HighlightKind, locatorJson: String, text: String, note: String?, shared: Boolean): Highlight? {
        if (locatorJson.length > Limits.LOCATOR_MAX) return null
        val now = clock.instant()
        val h = Highlight(
            UuidV7.generate(), book.roomId, 0, now, now, null, null, book.id, me, kind, locatorJson,
            text.take(Limits.HIGHLIGHT_TEXT_MAX), note?.trim()?.take(Limits.HIGHLIGHT_NOTE_MAX)?.ifEmpty { null }, shared,
        )
        store.writeLocal(book.roomId, h, OutboxOp.post("rooms/${book.roomId}/books/${book.id}/highlights",
            CreateHighlightRequest(h.id, kind, locatorJson, h.text, h.note, shared)))
        scheduler.kickOutbox()
        return h
    }

    suspend fun updateHighlight(h: Highlight, rawNote: String?, shared: Boolean) {
        val note = rawNote?.trim()?.take(Limits.HIGHLIGHT_NOTE_MAX)?.ifEmpty { null }
        if (note == h.note && shared == h.shared) return
        store.writeLocal(h.roomId, h.copy(note = note, shared = shared, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${h.roomId}/books/${h.bookId}/highlights/${h.id}",
                UpdateHighlightRequest(note = if (note != h.note) Patch.of(note) else Patch.Absent, shared = if (shared != h.shared) Patch.of(shared) else Patch.Absent)))
        scheduler.kickOutbox()
    }

    suspend fun deleteHighlight(h: Highlight) {
        store.writeLocal(h.roomId, h.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${h.roomId}/books/${h.bookId}/highlights/${h.id}"))
        scheduler.kickOutbox()
    }
}
