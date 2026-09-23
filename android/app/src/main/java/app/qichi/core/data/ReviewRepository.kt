package app.qichi.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.network.post
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiReviewFindingsRequest
import app.qichi.shared.api.ConvertFindingRequest
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.CreateAnnotationReplyRequest
import app.qichi.shared.api.CreateAnnotationRequest
import app.qichi.shared.api.CreateReviewRequest
import app.qichi.shared.api.CreateReviewVersionRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReviewDiff
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.UpdateAnnotationRequest
import app.qichi.shared.api.UpdateReviewRequest
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Clock
import java.util.UUID

/** 审稿文件选择器里给的类型（系统文件选择器按它过滤）。 */
val REVIEW_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/rtf",
    "text/plain",
    "text/csv",
)

class ReviewUploadException(message: String) : Exception(message)

/**
 * 审稿（P7-02）：审稿文件、版本、批注、讨论都是同步实体。
 * 新建和传新版本要先上传文件（需要联网），直接调接口再拉一次同步；批注、讨论、接受归档先写本机再经发件箱发出（离线也能写）。
 * 预览页（图片 + 文字层）不走同步：按需取，存在本机，离线也能看看过的。
 */
class ReviewRepository(
    private val context: Context,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val files: FileRepository,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")
    private val pageDir: File get() = File(context.filesDir, "review-pages").apply { mkdirs() }

    private inline fun <reified T : SyncEntity> observe(roomId: UUID, type: EntityType): Flow<List<Local<T>>> =
        db.entities().observeByType(roomId.toString(), type.wireName).map { rows -> rows.map { LocalStore.toLocal<T>(it) } }

    fun observeDocuments(roomId: UUID): Flow<List<ReviewDocument>> =
        observe<ReviewDocument>(roomId, EntityType.ReviewDocument).map { l -> l.map { it.value }.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt } }

    fun observeVersions(roomId: UUID): Flow<List<ReviewVersion>> =
        observe<ReviewVersion>(roomId, EntityType.ReviewVersion).map { l -> l.map { it.value } }

    fun observeAnnotations(roomId: UUID): Flow<List<Local<Annotation>>> = observe(roomId, EntityType.Annotation)

    fun observeReplies(roomId: UUID): Flow<List<Local<AnnotationReply>>> = observe(roomId, EntityType.AnnotationReply)

    fun observeFindings(roomId: UUID): Flow<List<Local<AiFinding>>> = observe(roomId, EntityType.AiFinding)

    // ── 审稿文件与版本（需要联网） ──

    private class Picked(val name: String, val mime: String, val temp: File)

    private suspend fun pick(uri: Uri): Picked {
        val resolver = context.contentResolver
        val name = withContext(Dispatchers.IO) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } ?: "文件"
        val temp = File(context.cacheDir, "incoming-review-${UUID.randomUUID()}")
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: throw ReviewUploadException("读不到这个文件")
        }
        if (temp.length() > Limits.FILE_MAX_BYTES) {
            temp.delete()
            throw ReviewUploadException("文件太大了（不能超过 100MB）")
        }
        return Picked(name, resolver.getType(uri) ?: "application/octet-stream", temp)
    }

    private suspend fun upload(roomId: UUID, picked: Picked, onProgress: (Float) -> Unit): UUID {
        val attachment = PreparedAttachment(FileKind.Review, picked.name, picked.mime, picked.temp.length(), Uri.fromFile(picked.temp), null, null) { picked.temp.inputStream() }
        return files.upload(roomId, UuidV7.generate(), attachment, FileKind.Review, onProgress).id
    }

    /** 选一个文件新建审稿：上传 → 新建（标题默认用文件名）→ 拉一次同步，版本和预览状态随后同步过来。 */
    suspend fun create(roomId: UUID, uri: Uri, onProgress: (Float) -> Unit = {}): UUID {
        val picked = pick(uri)
        try {
            val fileId = upload(roomId, picked, onProgress)
            val title = picked.name.substringBeforeLast('.').trim().ifEmpty { "未命名" }.take(Limits.REVIEW_TITLE_LENGTH.last)
            val doc: ReviewDocument = api.post("rooms/$roomId/reviews", CreateReviewRequest(UuidV7.generate(), title, CreateReviewVersionRequest(UuidV7.generate(), fileId)))
            store.applyServer(doc)
            pullQuietly(roomId)
            return doc.id
        } finally {
            withContext(Dispatchers.IO) { picked.temp.delete() }
        }
    }

    suspend fun uploadVersion(doc: ReviewDocument, uri: Uri, onProgress: (Float) -> Unit = {}): ReviewVersion {
        val picked = pick(uri)
        try {
            val fileId = upload(doc.roomId, picked, onProgress)
            val v: ReviewVersion = api.post("rooms/${doc.roomId}/reviews/${doc.id}/versions", CreateReviewVersionRequest(UuidV7.generate(), fileId))
            store.applyServer(v)
            pullQuietly(doc.roomId)
            return v
        } finally {
            withContext(Dispatchers.IO) { picked.temp.delete() }
        }
    }

    private suspend fun pullQuietly(roomId: UUID) {
        try {
            sync.pull(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun rename(doc: ReviewDocument, raw: String) {
        val title = raw.trim().take(Limits.REVIEW_TITLE_LENGTH.last)
        if (title.isEmpty() || title == doc.title) return
        store.writeLocal(doc.roomId, doc.copy(title = title, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${doc.roomId}/reviews/${doc.id}", UpdateReviewRequest(title = Patch.of(title))))
        scheduler.kickOutbox()
    }

    suspend fun delete(doc: ReviewDocument) {
        store.writeLocal(doc.roomId, doc.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${doc.roomId}/reviews/${doc.id}"))
        scheduler.kickOutbox()
    }

    // ── 预览页 ──

    /** 本机存过的预览页（没有返回 null）。 */
    suspend fun cachedPages(version: ReviewVersion): List<ReviewPage>? = withContext(Dispatchers.IO) {
        val file = File(pageDir, "${version.id}.json")
        if (!file.exists()) null else runCatching { QichiJson.decodeFromString(ListSerializer(ReviewPage.serializer()), file.readText()) }.getOrNull()
    }

    /** 预览页：本机有就用本机的，没有再联网取（预览生成好之后才会存下来）。 */
    suspend fun pages(doc: ReviewDocument, version: ReviewVersion): List<ReviewPage> {
        cachedPages(version)?.let { return it }
        val pages: List<ReviewPage> = api.get("rooms/${doc.roomId}/reviews/${doc.id}/versions/${version.version}/pages")
        if (version.previewStatus == PreviewStatus.Ready && pages.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                File(pageDir, "${version.id}.json").writeText(QichiJson.encodeToString(ListSerializer(ReviewPage.serializer()), pages))
            }
        }
        return pages
    }

    /** 两个版本的文字差异（需要联网）。 */
    suspend fun diff(doc: ReviewDocument, from: Int, to: Int): ReviewDiff = api.get("rooms/${doc.roomId}/reviews/${doc.id}/diff?from=$from&to=$to")

    // ── 批注与讨论（离线也能写） ──

    suspend fun annotate(doc: ReviewDocument, version: ReviewVersion, anchor: AnnotationAnchor, kind: AnnotationKind, rawBody: String): Annotation? {
        val body = rawBody.trim().take(Limits.ANNOTATION_BODY_LENGTH.last)
        if (body.isEmpty()) return null
        val a = anchor.copy(quote = anchor.quote?.trim()?.take(Limits.ANCHOR_QUOTE_MAX)?.ifEmpty { null })
        val now = clock.instant()
        val ann = Annotation(
            UuidV7.generate(), doc.roomId, 0, now, now, null, null, doc.id, version.id, a, me, kind, AnnotationStatus.Open, body,
            carriedFromId = null, anchorLost = false, resolvedBy = null, resolvedAt = null,
        )
        store.writeLocal(doc.roomId, ann, OutboxOp.post("rooms/${doc.roomId}/reviews/${doc.id}/annotations",
            CreateAnnotationRequest(ann.id, version.id, a, kind, body)))
        scheduler.kickOutbox()
        return ann
    }

    suspend fun editAnnotation(a: Annotation, rawBody: String) {
        val body = rawBody.trim().take(Limits.ANNOTATION_BODY_LENGTH.last)
        if (body.isEmpty() || body == a.body) return
        store.writeLocal(a.roomId, a.copy(body = body, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${a.roomId}/reviews/${a.documentId}/annotations/${a.id}", UpdateAnnotationRequest(body = Patch.of(body))))
        scheduler.kickOutbox()
    }

    suspend fun setStatus(a: Annotation, status: AnnotationStatus) {
        if (status == a.status) return
        val now = clock.instant()
        val resolved = status != AnnotationStatus.Open
        store.writeLocal(a.roomId, a.copy(status = status, resolvedBy = if (resolved) me else null, resolvedAt = if (resolved) now else null, updatedAt = now),
            OutboxOp.patch("rooms/${a.roomId}/reviews/${a.documentId}/annotations/${a.id}", UpdateAnnotationRequest(status = Patch.of(status))))
        scheduler.kickOutbox()
    }

    suspend fun deleteAnnotation(a: Annotation) {
        store.writeLocal(a.roomId, a.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${a.roomId}/reviews/${a.documentId}/annotations/${a.id}"))
        scheduler.kickOutbox()
    }

    suspend fun reply(a: Annotation, rawBody: String): AnnotationReply? {
        val body = rawBody.trim().take(Limits.ANNOTATION_REPLY_LENGTH.last)
        if (body.isEmpty()) return null
        val now = clock.instant()
        val r = AnnotationReply(UuidV7.generate(), a.roomId, 0, now, now, null, null, a.id, me, body)
        store.writeLocal(a.roomId, r, OutboxOp.post("rooms/${a.roomId}/annotations/${a.id}/replies", CreateAnnotationReplyRequest(r.id, body)))
        scheduler.kickOutbox()
        return r
    }

    // ── 审稿 AI（P7-03，发请求要联网；忽略、转批注可以离线） ──

    /** 本次授权 AI 审这一版（只发文字层）。结果是若干条 AI 发现，同步回来后显示。 */
    suspend fun askAi(doc: ReviewDocument, version: ReviewVersion, jobId: UUID): AiJobAccepted =
        api.post("rooms/${doc.roomId}/ai/review-findings", AiReviewFindingsRequest(jobId, doc.id, version.id))

    suspend fun aiJob(roomId: UUID, jobId: UUID): AiJob = api.get("rooms/$roomId/ai/jobs/$jobId")

    suspend fun dismiss(f: AiFinding) {
        if (f.status != FindingStatus.New) return
        store.writeLocal(f.roomId, f.copy(status = FindingStatus.Dismissed, resolvedBy = me, updatedAt = clock.instant()),
            OutboxOp.action("rooms/${f.roomId}/ai-findings/${f.id}/dismiss"))
        scheduler.kickOutbox()
    }

    /** 转成人工批注：批注由服务端建（钉在第一条证据上），同步回来后出现在批注里。 */
    suspend fun convert(f: AiFinding) {
        if (f.status != FindingStatus.New) return
        val annotationId = UuidV7.generate()
        store.writeLocal(f.roomId, f.copy(status = FindingStatus.Converted, convertedAnnotationId = annotationId, resolvedBy = me, updatedAt = clock.instant()),
            OutboxOp.post("rooms/${f.roomId}/ai-findings/${f.id}/convert", ConvertFindingRequest(annotationId), kind = OutboxOp.KIND_FINDING_CONVERT))
        scheduler.kickOutbox()
    }
}
