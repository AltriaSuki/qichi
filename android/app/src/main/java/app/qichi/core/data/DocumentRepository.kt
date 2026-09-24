package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.DocumentVersionRow
import app.qichi.core.database.DraftRow
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateDocCommentRequest
import app.qichi.shared.model.DocCategory
import app.qichi.shared.api.Patch
import app.qichi.shared.api.DocumentSearchHit
import kotlinx.coroutines.delay
import app.qichi.core.network.post
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.api.AiWriteRequest
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.Document
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.DocumentVersionPage
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.UpdateDocumentRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.time.Clock
import java.util.UUID

/**
 * 共同写作（docs/05-sync-offline.md §3.5）：
 * - 文稿列表是同步实体，照常「先写本机、再经发件箱」；
 * - 正在写的内容是本机草稿（带基线版本），每次输入都存，切走、杀掉 App 都不丢；
 * - 保存 = 把草稿作为新版本放进发件箱；基线落后时服务端 409，草稿原样留着，界面进入「重基线」；
 * - 版本不可变，打开过的版本正文缓存在本机，离线也能读。
 */
class DocumentRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val drafts: DraftStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    // ── 文稿 ──

    /** 房间里的文稿（不含回收站）：置顶的在前，其余最近更新的在前。 */
    fun observeDocuments(roomId: UUID): Flow<List<Local<Document>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Document.wireName)
            .map { rows ->
                rows.map { LocalStore.toLocal<Document>(it) }
                    .sortedWith(compareByDescending<Local<Document>> { it.value.pinned }.thenByDescending { it.value.updatedAt })
            }

    fun observeDocument(roomId: UUID, id: UUID): Flow<Local<Document>?> =
        observeDocuments(roomId).map { list -> list.firstOrNull { it.value.id == id } }

    /** 标题空白时不建。 */
    suspend fun create(roomId: UUID, rawTitle: String): Document? {
        val title = rawTitle.trim().take(Limits.DOCUMENT_TITLE_LENGTH.last).ifEmpty { return null }
        val now = clock.instant()
        val doc = Document(UuidV7.generate(), roomId, 0, now, now, null, null, title, me, 0, null, 0)
        store.writeLocal(roomId, doc, OutboxOp.post("rooms/$roomId/documents", CreateDocumentRequest(doc.id, title)))
        scheduler.kickOutbox()
        return doc
    }

    suspend fun rename(doc: Document, rawTitle: String) {
        val title = rawTitle.trim().take(Limits.DOCUMENT_TITLE_LENGTH.last)
        if (title.isEmpty() || title == doc.title) return
        store.writeLocal(doc.roomId, doc.copy(title = title, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${doc.roomId}/documents/${doc.id}", UpdateDocumentRequest(title = Patch.of(title))))
        scheduler.kickOutbox()
    }

    /** 置顶 / 取消置顶（两个人看到的一样，可以离线改）。 */
    suspend fun setPinned(doc: Document, pinned: Boolean) {
        if (doc.pinned == pinned) return
        store.writeLocal(doc.roomId, doc.copy(pinned = pinned),
            OutboxOp.patch("rooms/${doc.roomId}/documents/${doc.id}", UpdateDocumentRequest(pinned = Patch.of(pinned))))
        scheduler.kickOutbox()
    }

    /** 改分类；null = 不分类。 */
    suspend fun setCategory(doc: Document, category: DocCategory?) {
        if (doc.category == category) return
        store.writeLocal(doc.roomId, doc.copy(category = category),
            OutboxOp.patch("rooms/${doc.roomId}/documents/${doc.id}", UpdateDocumentRequest(category = Patch.of(category))))
        scheduler.kickOutbox()
    }

    /** 正文搜索（要联网；标题在本机就能搜）。 */
    suspend fun search(roomId: UUID, query: String): List<DocumentSearchHit> =
        api.get("rooms/$roomId/documents/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))

    suspend fun delete(doc: Document) {
        store.writeLocal(doc.roomId, doc.copy(deletedAt = clock.instant(), deletedBy = me),
            OutboxOp.delete("rooms/${doc.roomId}/documents/${doc.id}"))
        scheduler.kickOutbox()
    }

    // ── 段落旁留言（P9-03，可以离线写，联网补发） ──

    /** 这篇文稿的留言（开头和回复，不含回收站里的开头；开头被删了的回复也不要），按时间从早到晚。 */
    fun observeComments(roomId: UUID, documentId: UUID): Flow<List<Local<DocComment>>> =
        db.entities().observeByType(roomId.toString(), EntityType.DocComment.wireName)
            .map { rows ->
                val all = rows.map { LocalStore.toLocal<DocComment>(it) }.filter { it.value.documentId == documentId }
                val liveRoots = all.filter { it.value.parentId == null && it.value.deletedAt == null }.map { it.value.id }.toSet()
                all.filter { it.value.deletedAt == null && (it.value.parentId == null || it.value.parentId in liveRoots) }
                    .sortedBy { it.value.createdAt }
            }

    /** 钉在 [quote] 上的新留言；正文空白时不建。 */
    suspend fun addComment(doc: Document, quote: String, rawBody: String): DocComment? {
        val body = rawBody.trim().take(Limits.DOC_COMMENT_LENGTH.last).ifEmpty { return null }
        val q = quote.replace(Regex("\\s+"), " ").trim().take(Limits.DOC_COMMENT_QUOTE_MAX).ifEmpty { return null }
        val now = clock.instant()
        val c = DocComment(UuidV7.generate(), doc.roomId, 0, now, now, null, null, doc.id, null, me, body, q, doc.latestVersion, null, null)
        store.writeLocal(doc.roomId, c, OutboxOp.post("rooms/${doc.roomId}/documents/${doc.id}/comments",
            CreateDocCommentRequest(c.id, body, quote = q, version = doc.latestVersion)))
        scheduler.kickOutbox()
        return c
    }

    suspend fun reply(root: DocComment, rawBody: String): DocComment? {
        val body = rawBody.trim().take(Limits.DOC_COMMENT_LENGTH.last).ifEmpty { return null }
        val now = clock.instant()
        val c = DocComment(UuidV7.generate(), root.roomId, 0, now, now, null, null, root.documentId, root.id, me, body, null, null, null, null)
        store.writeLocal(root.roomId, c, OutboxOp.post("rooms/${root.roomId}/documents/${root.documentId}/comments",
            CreateDocCommentRequest(c.id, body, parentId = root.id)))
        scheduler.kickOutbox()
        return c
    }

    suspend fun setResolved(root: DocComment, resolved: Boolean) {
        if ((root.resolvedAt != null) == resolved) return
        val now = clock.instant()
        store.writeLocal(root.roomId, root.copy(resolvedAt = if (resolved) now else null, resolvedBy = if (resolved) me else null, updatedAt = now),
            OutboxOp.action("rooms/${root.roomId}/doc-comments/${root.id}/" + if (resolved) "resolve" else "reopen"))
        scheduler.kickOutbox()
    }

    /** 删掉自己写的讨论开头（进回收站）。 */
    suspend fun deleteComment(root: DocComment) {
        if (root.authorId != me || root.parentId != null) return
        store.writeLocal(root.roomId, root.copy(deletedAt = clock.instant(), deletedBy = me),
            OutboxOp.delete("rooms/${root.roomId}/doc-comments/${root.id}"))
        scheduler.kickOutbox()
    }

    // ── 写作助手（P9-04 / P9-05，要联网；结果只是建议，由人决定用不用） ──

    /**
     * 请 AI 帮忙并等结果：发出请求后每 1.5 秒问一次，最多等 3 分钟。
     * 失败时抛 [ApiException]（AI 没开、额度用完）或 [AssistFailed]（没得到结果）。
     */
    suspend fun assist(roomId: UUID, req: AiWriteRequest): String {
        api.post<AiJobAccepted>("rooms/$roomId/ai/write-assist", req)
        val deadline = clock.millis() + ASSIST_TIMEOUT_MS
        while (clock.millis() < deadline) {
            delay(ASSIST_POLL_MS)
            val job = runCatching { api.get<AiJob>("rooms/$roomId/ai/jobs/${req.jobId}") }.getOrNull() ?: continue
            when (job.status) {
                AiJobStatus.Done -> return job.resultText ?: throw AssistFailed("没有得到结果")
                AiJobStatus.Failed -> throw AssistFailed(job.error ?: "没有得到结果")
                else -> Unit
            }
        }
        throw AssistFailed("AI 想得太久了，再试一次")
    }

    class AssistFailed(message: String) : Exception(message)

    // ── 草稿 ──

    fun observeDraft(roomId: UUID, documentId: UUID): Flow<DraftRow?> = drafts.observeRow(roomId, DraftStore.documentKey(documentId))

    /**
     * 每次输入都存。[baseVersion] 是这段内容所依据的版本；和该版本的正文 [baseBody] 一样时删掉草稿（没有未保存的内容）。
     */
    suspend fun writeDraft(roomId: UUID, documentId: UUID, text: String, baseVersion: Int, baseBody: String?) {
        val key = DraftStore.documentKey(documentId)
        if (text == (baseBody ?: if (baseVersion == 0) "" else null)) {
            drafts.delete(roomId, key)
        } else {
            drafts.saveVersioned(roomId, key, text.take(Limits.DOCUMENT_BODY_MAX), baseVersion)
        }
    }

    /** 有未保存内容的文稿（列表上标一个小点）。 */
    fun observeDraftIds(roomId: UUID): Flow<Set<UUID>> =
        db.drafts().observePrefix(roomId.toString(), "doc:").map { rows ->
            rows.mapNotNull { runCatching { UUID.fromString(it.key.removePrefix("doc:")) }.getOrNull() }.toSet()
        }

    /** 放弃未保存的内容，回到最新版本。 */
    suspend fun discardDraft(roomId: UUID, documentId: UUID) = drafts.delete(roomId, DraftStore.documentKey(documentId))

    /** 重基线：保留自己的内容，但改为基于 [latestVersion] 继续写（之后正常保存）。 */
    suspend fun rebase(roomId: UUID, documentId: UUID, latestVersion: Int) {
        val key = DraftStore.documentKey(documentId)
        val draft = drafts.loadRow(roomId, key) ?: return
        drafts.saveVersioned(roomId, key, draft.text, latestVersion)
    }

    // ── 保存与版本 ──

    /** 这篇文稿有没有正在发出的保存。 */
    fun observeSaving(documentId: UUID): Flow<Boolean> =
        db.outbox().observeCountFor(EntityType.Document.wireName, documentId.toString(), OutboxOp.KIND_DOC_VERSION).map { it > 0 }

    /**
     * 把草稿保存为新版本。已经有一次保存在排队时不再重复放进发件箱。
     * @return 是否放进了发件箱
     */
    suspend fun save(doc: Document): Boolean {
        val draft = drafts.loadRow(doc.roomId, DraftStore.documentKey(doc.id)) ?: return false
        val base = draft.baseVersion ?: return false
        return enqueueSave(doc, SaveDocumentVersionRequest(UuidV7.generate(), base, draft.text))
    }

    /** 旧版另存为新版：内容换成版本 [version] 的正文，基于最新版本保存。 */
    suspend fun restore(doc: Document, version: Int, body: String): Boolean {
        drafts.saveVersioned(doc.roomId, DraftStore.documentKey(doc.id), body, doc.latestVersion)
        return enqueueSave(doc, SaveDocumentVersionRequest(UuidV7.generate(), doc.latestVersion, body, restoredFromVersion = version))
    }

    private suspend fun enqueueSave(doc: Document, request: SaveDocumentVersionRequest): Boolean {
        val queued = db.transaction {
            if (db.outbox().countFor(EntityType.Document.wireName, doc.id.toString(), OutboxOp.KIND_DOC_VERSION) > 0) return@transaction false
            store.enqueue(
                doc.roomId, EntityType.Document, doc.id,
                OutboxOp.post("rooms/${doc.roomId}/documents/${doc.id}/versions", request, kind = OutboxOp.KIND_DOC_VERSION),
            )
            true
        }
        if (queued) scheduler.kickOutbox()
        return queued
    }

    /** 本机缓存的版本（新的在前）。 */
    fun observeVersions(documentId: UUID): Flow<List<DocumentVersionRow>> = db.documentVersions().observe(documentId.toString())

    fun observeVersion(documentId: UUID, version: Int): Flow<DocumentVersionRow?> =
        db.documentVersions().observeOne(documentId.toString(), version)

    /** 在线时补齐版本列表（不含正文）。 */
    suspend fun refreshVersions(roomId: UUID, documentId: UUID) {
        var cursor: String? = null
        repeat(MAX_VERSION_PAGES) {
            val after = cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val page = api.get<DocumentVersionPage>("rooms/$roomId/documents/$documentId/versions?limit=100$after")
            db.transaction {
                page.items.forEach { v ->
                    db.documentVersions().insertInfo(
                        v.id.toString(), roomId.toString(), documentId.toString(), v.version, v.baseVersion,
                        v.authorId.toString(), v.charCount, v.restoredFromVersion, v.createdAt.toEpochMilli(),
                    )
                }
            }
            cursor = page.nextCursor ?: return
        }
    }

    /** 某个版本的正文：本机有就用本机的，没有就取一次存下来。离线又没缓存时抛出网络错误。 */
    suspend fun loadBody(roomId: UUID, documentId: UUID, version: Int): String {
        db.documentVersions().get(documentId.toString(), version)?.body?.let { return it }
        val v = api.get<DocumentVersion>("rooms/$roomId/documents/$documentId/versions/$version")
        db.documentVersions().upsert(
            DocumentVersionRow(
                v.id.toString(), roomId.toString(), documentId.toString(), v.version, v.baseVersion, v.authorId.toString(),
                v.charCount, v.restoredFromVersion, v.createdAt.toEpochMilli(), v.body,
            ),
        )
        return v.body
    }

    private companion object {
        const val ASSIST_POLL_MS = 1_500L
        const val ASSIST_TIMEOUT_MS = 180_000L
        const val MAX_VERSION_PAGES = 20
    }
}
