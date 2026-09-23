package app.qichi.server.review

import app.qichi.server.db.AiFindings
import app.qichi.server.db.AnnotationReplies
import app.qichi.server.db.Annotations
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Files
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.ReviewPages
import app.qichi.server.db.ReviewVersions
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Tx
import app.qichi.server.db.tx
import app.qichi.server.files.FileService
import app.qichi.server.files.ReviewFiles
import app.qichi.server.jobs.JobQueue
import app.qichi.server.jobs.QueuedJob
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.ConvertFindingRequest
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.CreateAnnotationReplyRequest
import app.qichi.shared.api.CreateAnnotationRequest
import app.qichi.shared.api.CreateReviewRequest
import app.qichi.shared.api.CreateReviewVersionRequest
import app.qichi.shared.api.DiffKind
import app.qichi.shared.api.Patch
import app.qichi.shared.api.ReviewDiff
import app.qichi.shared.api.ReviewDiffLine
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.TextBlock
import app.qichi.shared.api.UpdateAnnotationRequest
import app.qichi.shared.api.UpdateReviewRequest
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.ReviewFormat
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.Diff
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.nio.file.Files as NioFiles
import java.time.Clock
import java.util.UUID

private val log = LoggerFactory.getLogger(ReviewService::class.java)

fun ResultRow.toReviewDocument() = ReviewDocument(
    id = this[ReviewDocuments.id], roomId = this[ReviewDocuments.roomId], seq = this[ReviewDocuments.seq],
    createdAt = this[ReviewDocuments.createdAt], updatedAt = this[ReviewDocuments.updatedAt],
    deletedAt = this[ReviewDocuments.deletedAt], deletedBy = this[ReviewDocuments.deletedBy],
    title = this[ReviewDocuments.title], createdBy = this[ReviewDocuments.createdBy], latestVersion = this[ReviewDocuments.latestVersion],
)

/** 版本与原文件名一起查。 */
fun reviewVersionQuery() = ReviewVersions.join(Files, JoinType.INNER, ReviewVersions.fileId, Files.id).selectAll()

fun ResultRow.toReviewVersion() = ReviewVersion(
    id = this[ReviewVersions.id], roomId = this[ReviewVersions.roomId], seq = this[ReviewVersions.seq],
    createdAt = this[ReviewVersions.createdAt], updatedAt = this[ReviewVersions.updatedAt],
    deletedAt = this[ReviewVersions.deletedAt], deletedBy = this[ReviewVersions.deletedBy],
    documentId = this[ReviewVersions.documentId], version = this[ReviewVersions.version], fileId = this[ReviewVersions.fileId],
    fileName = this[Files.fileName], format = fromWire(this[ReviewVersions.format]), uploadedBy = this[ReviewVersions.uploadedBy],
    previewStatus = fromWire(this[ReviewVersions.previewStatus]), pageCount = this[ReviewVersions.pageCount],
    previewError = this[ReviewVersions.previewError],
)

fun ResultRow.toAnnotation() = Annotation(
    id = this[Annotations.id], roomId = this[Annotations.roomId], seq = this[Annotations.seq],
    createdAt = this[Annotations.createdAt], updatedAt = this[Annotations.updatedAt],
    deletedAt = this[Annotations.deletedAt], deletedBy = this[Annotations.deletedBy],
    documentId = this[Annotations.documentId], versionId = this[Annotations.versionId], anchor = this[Annotations.anchor],
    authorId = this[Annotations.authorId], kind = fromWire(this[Annotations.kind]), status = fromWire(this[Annotations.status]),
    body = this[Annotations.body], carriedFromId = this[Annotations.carriedFromId], anchorLost = this[Annotations.anchorLost],
    resolvedBy = this[Annotations.resolvedBy], resolvedAt = this[Annotations.resolvedAt],
)

fun ResultRow.toAiFinding() = AiFinding(
    id = this[AiFindings.id], roomId = this[AiFindings.roomId], seq = this[AiFindings.seq],
    createdAt = this[AiFindings.createdAt], updatedAt = this[AiFindings.updatedAt],
    deletedAt = this[AiFindings.deletedAt], deletedBy = this[AiFindings.deletedBy],
    documentId = this[AiFindings.documentId], versionId = this[AiFindings.versionId], jobId = this[AiFindings.jobId],
    requestedBy = this[AiFindings.requestedBy], title = this[AiFindings.title], body = this[AiFindings.body],
    evidence = this[AiFindings.evidence], status = fromWire(this[AiFindings.status]),
    convertedAnnotationId = this[AiFindings.convertedAnnotationId], carriedFromId = this[AiFindings.carriedFromId],
    goneInVersion = this[AiFindings.goneInVersion], resolvedBy = this[AiFindings.resolvedBy],
)

fun ResultRow.toAnnotationReply() = AnnotationReply(
    id = this[AnnotationReplies.id], roomId = this[AnnotationReplies.roomId], seq = this[AnnotationReplies.seq],
    createdAt = this[AnnotationReplies.createdAt], updatedAt = this[AnnotationReplies.updatedAt],
    deletedAt = this[AnnotationReplies.deletedAt], deletedBy = this[AnnotationReplies.deletedBy],
    annotationId = this[AnnotationReplies.annotationId], authorId = this[AnnotationReplies.authorId], body = this[AnnotationReplies.body],
)

/**
 * 审稿（P7-01）：审稿文件、不可变版本、后台生成安全预览、批注与提议、讨论、版本差异。
 * 两位成员都能传新版本、批注、接受或归档；批注正文只有作者能改，只能删自己的。
 */
class ReviewService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
    private val writer: RoomWriter,
    private val files: FileService,
    private val jobs: JobQueue,
    private val converter: DocumentConverter?,
    private val clock: Clock,
) {
    init {
        jobs.register(JOB_PREVIEW) { job -> buildPreview(job) }
    }

    private fun document(id: UUID) = ReviewDocuments.selectAll().where { ReviewDocuments.id eq id }.singleOrNull()?.toReviewDocument()
    private fun version(id: UUID) = reviewVersionQuery().where { ReviewVersions.id eq id }.singleOrNull()?.toReviewVersion()
    private fun annotation(id: UUID) = Annotations.selectAll().where { Annotations.id eq id }.singleOrNull()?.toAnnotation()
    private fun reply(id: UUID) = AnnotationReplies.selectAll().where { AnnotationReplies.id eq id }.singleOrNull()?.toAnnotationReply()

    private fun liveDocument(roomId: UUID, id: UUID) = document(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()

    private fun versionByNumber(documentId: UUID, number: Int) =
        reviewVersionQuery().where { (ReviewVersions.documentId eq documentId) and (ReviewVersions.version eq number) }.singleOrNull()?.toReviewVersion()

    private fun cleanTitle(raw: String): String {
        val t = raw.trim()
        validate { check(t.length in Limits.REVIEW_TITLE_LENGTH, "title", "标题 1–${Limits.REVIEW_TITLE_LENGTH.last} 字") }
        return t
    }

    private fun cleanBody(raw: String, range: IntRange): String {
        val t = raw.trim()
        validate { check(t.length in range, "body", "${range.first}–${range.last} 字") }
        return t
    }

    // ── 审稿文件与版本 ──

    suspend fun list(userId: UUID, roomId: UUID): List<ReviewDocument> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        ReviewDocuments.selectAll().where { (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }
            .orderBy(ReviewDocuments.updatedAt, SortOrder.DESC).map { it.toReviewDocument() }
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateReviewRequest): Pair<ReviewDocument, Boolean> {
        val title = cleanTitle(req.title)
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            val existing = document(req.id)
            if (existing != null) {
                if (existing.roomId != roomId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                return@tx existing to false
            }
            val format = reviewFileFormat(roomId, req.firstVersion.fileId)
            if (version(req.firstVersion.id) != null) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            val created = writes.create(this, roomId, userId, EntityType.ReviewDocument, req.id, ReviewDocuments, ::document) {
                it[ReviewDocuments.title] = title
                it[ReviewDocuments.createdBy] = userId
                it[ReviewDocuments.latestVersion] = 1
            }
            insertVersion(this, roomId, userId, req.id, req.firstVersion, 1, format)
            created
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateReviewRequest): ReviewDocument {
        val title = (req.title as? Patch.Value)?.value?.let(::cleanTitle)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = liveDocument(roomId, id)
            if (title != null && title != current.title) {
                writes.update(this, roomId, userId, EntityType.ReviewDocument, id, ReviewDocuments) { it[ReviewDocuments.title] = title }
            }
            document(id)!!
        }
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): ReviewDocument = db.tx {
        rooms.requireMember(roomId, userId)
        val current = document(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.ReviewDocument, id, ReviewDocuments)
        document(id)!!
    }

    suspend fun createVersion(userId: UUID, roomId: UUID, documentId: UUID, req: CreateReviewVersionRequest): Pair<ReviewVersion, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val doc = liveDocument(roomId, documentId)
        val existing = version(req.id)
        if (existing != null) {
            if (existing.roomId != roomId || existing.documentId != documentId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            return@tx existing to false
        }
        val format = reviewFileFormat(roomId, req.fileId)
        val number = doc.latestVersion + 1
        writes.update(this, roomId, userId, EntityType.ReviewDocument, documentId, ReviewDocuments) { it[ReviewDocuments.latestVersion] = number }
        insertVersion(this, roomId, userId, documentId, req, number, format) to true
    }

    /** 文件必须是这个房间里传的审稿文件；按内容认出类别。 */
    private fun reviewFileFormat(roomId: UUID, fileId: UUID): ReviewFormat {
        val row = Files.selectAll().where { (Files.id eq fileId) and (Files.roomId eq roomId) and (Files.kind eq FileKind.Review.wireName) }.singleOrNull()
        validate { check(row != null, "fileId", "要先把文件传上来（kind = review）") }
        val path = files.resolve(row!![Files.storagePath])
        return ReviewFiles.sniff(path, row[Files.fileName])?.format
            ?: throw ApiException(ProblemCode.UnsupportedMediaType, "不支持这种文件")
    }

    private fun insertVersion(tx: Tx, roomId: UUID, userId: UUID, documentId: UUID, req: CreateReviewVersionRequest, number: Int, format: ReviewFormat): ReviewVersion {
        val (created, _) = writes.create(tx, roomId, userId, EntityType.ReviewVersion, req.id, ReviewVersions, ::version) {
            it[ReviewVersions.documentId] = documentId
            it[ReviewVersions.version] = number
            it[ReviewVersions.fileId] = req.fileId
            it[ReviewVersions.format] = format.wireName
            it[ReviewVersions.uploadedBy] = userId
            it[ReviewVersions.previewStatus] = PreviewStatus.Pending.wireName
        }
        jobs.enqueue(tx, JOB_PREVIEW, buildJsonObject { put("versionId", req.id.toString()) }, maxAttempts = 3)
        return created
    }

    // ── 预览 ──

    suspend fun pages(userId: UUID, roomId: UUID, documentId: UUID, number: Int): List<ReviewPage> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        liveDocument(roomId, documentId)
        val v = versionByNumber(documentId, number) ?: notFound()
        loadPages(v.id)
    }

    private fun loadPages(versionId: UUID): List<ReviewPage> =
        ReviewPages.selectAll().where { ReviewPages.versionId eq versionId }.orderBy(ReviewPages.pageNo).map {
            ReviewPage(it[ReviewPages.pageNo], it[ReviewPages.width], it[ReviewPages.height], it[ReviewPages.imageFileId], it[ReviewPages.textLayer], it[ReviewPages.images])
        }

    suspend fun diff(userId: UUID, roomId: UUID, documentId: UUID, from: Int, to: Int): ReviewDiff = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        liveDocument(roomId, documentId)
        val old = versionByNumber(documentId, from) ?: notFound()
        val new = versionByNumber(documentId, to) ?: notFound()
        validate {
            check(from != to, "to", "要选两个不同的版本")
            check(old.previewStatus == PreviewStatus.Ready && new.previewStatus == PreviewStatus.Ready, "to", "预览还没生成好")
        }
        ReviewDiff(from, to, diffPages(loadPages(old.id), loadPages(new.id)))
    }

    /** 把每个版本的文字块按阅读顺序排成一行一块，逐行对比，再把行号换回页码。 */
    internal fun diffPages(old: List<ReviewPage>, new: List<ReviewPage>): List<ReviewDiffLine> {
        fun flatten(pages: List<ReviewPage>) = pages.flatMap { p -> p.blocks.map { p.page to normalize(it.text) } }.filter { it.second.isNotEmpty() }
        val a = flatten(old)
        val b = flatten(new)
        return Diff.lines(a.joinToString("\n") { it.second }, b.joinToString("\n") { it.second }).map { line ->
            ReviewDiffLine(
                kind = when (line.kind) { Diff.Kind.Same -> DiffKind.Same; Diff.Kind.Removed -> DiffKind.Removed; Diff.Kind.Added -> DiffKind.Added },
                text = line.text,
                oldPage = line.oldNumber?.let { a[it - 1].first },
                newPage = line.newNumber?.let { b[it - 1].first },
            )
        }
    }

    /** 后台任务：生成预览（非 PDF 先转成 PDF），写入各页，然后把上一版没处理完的批注带过来。 */
    private suspend fun buildPreview(job: QueuedJob) {
        val versionId = UUID.fromString(job.payload["versionId"]!!.jsonPrimitive.content)
        val (v, source) = db.tx(readOnly = true) {
            val v = version(versionId) ?: return@tx null
            val path = Files.select(Files.storagePath).where { Files.id eq v.fileId }.single()[Files.storagePath]
            v to files.resolve(path)
        } ?: return
        if (v.previewStatus != PreviewStatus.Pending) return
        val written = mutableListOf<String>()
        var tempPdf: java.nio.file.Path? = null
        try {
            val pdf = if (v.format == ReviewFormat.Pdf) source else {
                val c = converter ?: throw PreviewFailure("服务器还没有配置文档转换，暂时只能预览 PDF")
                val out = withContext(Dispatchers.IO) { NioFiles.createTempFile("qichi-review-", ".pdf") }
                tempPdf = out
                c.toPdf(source, extensionFor(v), out)
                out
            }
            val rendered = mutableListOf<Pair<Int, PdfPreview.Page>>()
            val count = withContext(Dispatchers.IO) {
                PdfPreview.render(pdf, v.format, Limits.REVIEW_MAX_PAGES) { n, page -> rendered += n to page }
            }
            db.tx {
                val current = version(versionId) ?: return@tx
                if (current.previewStatus != PreviewStatus.Pending) return@tx
                val base = v.fileName.substringBeforeLast('.').take(60)
                for ((n, page) in rendered) {
                    val (fileId, path) = files.insertGenerated(
                        v.roomId, v.uploadedBy, FileKind.Review, "$base-v${v.version}-p$n.jpg", "image/jpeg",
                        page.jpeg, page.pixelWidth, page.pixelHeight,
                    )
                    written += path
                    ReviewPages.insert {
                        it[ReviewPages.versionId] = versionId
                        it[pageNo] = n
                        it[width] = page.width
                        it[height] = page.height
                        it[imageFileId] = fileId
                        it[textLayer] = page.blocks
                        it[images] = page.images
                    }
                }
                systemUpdateVersion(this, v.roomId, versionId, PreviewStatus.Ready, count, null)
                val pages = loadPages(versionId)
                carryAnnotations(this, current, pages)
                carryFindings(this, current, pages)
            }
        } catch (e: PreviewFailure) {
            markFailed(v, e.message ?: "预览没能生成")
            files.deleteStored(written)
        } catch (e: Exception) {
            files.deleteStored(written)
            if (job.isLastAttempt) markFailed(v, "预览没能生成，可以稍后重新上传试试")
            throw e
        } finally {
            tempPdf?.let { withContext(Dispatchers.IO) { NioFiles.deleteIfExists(it) } }
        }
    }

    /** 转换时按什么格式读：原文件名的扩展名，没有时按类别猜。 */
    private fun extensionFor(v: ReviewVersion): String {
        val ext = v.fileName.substringAfterLast('.', "").lowercase()
        if (ext.matches(Regex("[a-z0-9]{2,5}"))) return ext
        return when (v.format) {
            ReviewFormat.Pdf -> "pdf"
            ReviewFormat.Text -> "docx"
            ReviewFormat.Sheet -> "xlsx"
            ReviewFormat.Slides -> "pptx"
        }
    }

    private suspend fun markFailed(v: ReviewVersion, reason: String) {
        log.info("审稿预览失败：{} {}", v.id, reason)
        db.tx {
            val current = version(v.id) ?: return@tx
            if (current.previewStatus == PreviewStatus.Pending) systemUpdateVersion(this, v.roomId, v.id, PreviewStatus.Failed, null, reason)
        }
    }

    /** 服务端自己改版本的预览状态（没有操作人）。 */
    private fun systemUpdateVersion(tx: Tx, roomId: UUID, id: UUID, status: PreviewStatus, pages: Int?, error: String?) {
        val now = clock.instant()
        val seq = writer.change(tx, roomId, EntityType.ReviewVersion, id, null, now)
        ReviewVersions.update({ ReviewVersions.id eq id }) {
            it[ReviewVersions.seq] = seq
            it[updatedAt] = now
            it[previewStatus] = status.wireName
            it[pageCount] = pages
            it[previewError] = error
        }
    }

    /**
     * 跨版本追踪：上一个预览好的版本里还没处理完（open）的批注，带到这个版本（新的一条，carriedFromId 指向旧的）。
     * 按原文摘录找新位置；找不到就钉在原页码（超出就放最后一页），标上 anchorLost。
     */
    private fun carryAnnotations(tx: Tx, target: ReviewVersion, pages: List<ReviewPage>) {
        if (pages.isEmpty()) return
        val previous = ReviewVersions.selectAll()
            .where {
                (ReviewVersions.documentId eq target.documentId) and (ReviewVersions.version less target.version) and
                    (ReviewVersions.previewStatus eq PreviewStatus.Ready.wireName)
            }
            .orderBy(ReviewVersions.version, SortOrder.DESC).limit(1).singleOrNull()?.get(ReviewVersions.id) ?: return
        val open = Annotations.selectAll().where {
            (Annotations.versionId eq previous) and (Annotations.status eq AnnotationStatus.Open.wireName) and Annotations.deletedAt.isNull()
        }.map { it.toAnnotation() }
        for (old in open) {
            val already = Annotations.select(Annotations.id).where { (Annotations.carriedFromId eq old.id) and (Annotations.versionId eq target.id) }.any()
            if (already) continue
            val (anchor, lost) = Relocate.anchor(old.anchor, pages)
            val id = UuidV7.generate()
            val now = clock.instant()
            val seq = writer.change(tx, target.roomId, EntityType.Annotation, id, null, now)
            Annotations.insert {
                it[Annotations.id] = id
                it[roomId] = target.roomId
                it[Annotations.seq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[documentId] = target.documentId
                it[versionId] = target.id
                it[Annotations.anchor] = anchor
                it[authorId] = old.authorId
                it[kind] = old.kind.wireName
                it[status] = AnnotationStatus.Open.wireName
                it[body] = old.body
                it[carriedFromId] = old.id
                it[anchorLost] = lost
            }
        }
    }

    /**
     * AI 发现的跨版本追踪：上一个预览好的版本里还是「新的」发现，证据原文在这版里还都找得到就带过来；
     * 找不到了就在旧的上面记下「在第几版里找不到了」（可能已经改好）。
     */
    private fun carryFindings(tx: Tx, target: ReviewVersion, pages: List<ReviewPage>) {
        if (pages.isEmpty()) return
        val previous = ReviewVersions.selectAll()
            .where {
                (ReviewVersions.documentId eq target.documentId) and (ReviewVersions.version less target.version) and
                    (ReviewVersions.previewStatus eq PreviewStatus.Ready.wireName)
            }
            .orderBy(ReviewVersions.version, SortOrder.DESC).limit(1).singleOrNull()?.get(ReviewVersions.id) ?: return
        val open = AiFindings.selectAll().where {
            (AiFindings.versionId eq previous) and (AiFindings.status eq FindingStatus.New.wireName) and AiFindings.deletedAt.isNull()
        }.map { it.toAiFinding() }
        val blocks = pages.flatMap { p -> p.blocks.map { b -> Triple(p.page, b, FindingParser.compact(b.text)) } }
        for (old in open) {
            if (AiFindings.select(AiFindings.id).where { (AiFindings.carriedFromId eq old.id) and (AiFindings.versionId eq target.id) }.any()) continue
            val moved = old.evidence.map { e ->
                val q = FindingParser.compact(e.quote)
                blocks.firstOrNull { it.third.contains(q) }?.let { (page, block, _) -> e.copy(page = page, ref = block.id, rect = block.rect) }
            }
            val now = clock.instant()
            if (moved.all { it != null }) {
                val id = UuidV7.generate()
                val seq = writer.change(tx, target.roomId, EntityType.AiFinding, id, null, now)
                AiFindings.insert {
                    it[AiFindings.id] = id
                    it[roomId] = target.roomId
                    it[AiFindings.seq] = seq
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[documentId] = target.documentId
                    it[versionId] = target.id
                    it[jobId] = old.jobId
                    it[requestedBy] = old.requestedBy
                    it[title] = old.title
                    it[body] = old.body
                    it[evidence] = moved.filterNotNull()
                    it[status] = FindingStatus.New.wireName
                    it[carriedFromId] = old.id
                }
            } else if (old.goneInVersion == null) {
                val seq = writer.change(tx, target.roomId, EntityType.AiFinding, old.id, null, now)
                AiFindings.update({ AiFindings.id eq old.id }) {
                    it[AiFindings.seq] = seq
                    it[updatedAt] = now
                    it[goneInVersion] = target.version
                }
            }
        }
    }

    // ── AI 发现：忽略、转成批注（AI 只提出，人来决定） ──

    private fun finding(id: UUID) = AiFindings.selectAll().where { AiFindings.id eq id }.singleOrNull()?.toAiFinding()

    private fun liveFinding(roomId: UUID, id: UUID): AiFinding {
        val f = finding(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
        liveDocument(roomId, f.documentId)
        return f
    }

    suspend fun dismissFinding(userId: UUID, roomId: UUID, id: UUID): AiFinding = db.tx {
        rooms.requireMember(roomId, userId)
        val f = liveFinding(roomId, id)
        if (f.status == FindingStatus.New) {
            writes.update(this, roomId, userId, EntityType.AiFinding, id, AiFindings) {
                it[AiFindings.status] = FindingStatus.Dismissed.wireName
                it[AiFindings.resolvedBy] = userId
            }
        }
        finding(id)!!
    }

    /** 转成一条人工批注：作者是自己，钉在第一条证据那一块上；正文是发现的概括和说明。 */
    suspend fun convertFinding(userId: UUID, roomId: UUID, id: UUID, req: ConvertFindingRequest): Pair<Annotation, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val f = liveFinding(roomId, id)
        f.convertedAnnotationId?.let { existing -> annotation(existing)?.let { return@tx it to false } }
        annotation(req.annotationId)?.let { throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用") }
        val first = f.evidence.first()
        val page = loadPages(f.versionId).firstOrNull { it.page == first.page }
        val kind = page?.blocks?.firstOrNull { it.id == first.ref }?.kind ?: AnchorKind.Paragraph
        val body = listOf(f.title, f.body).filter { it.isNotBlank() }.joinToString("\n").take(Limits.ANNOTATION_BODY_LENGTH.last)
        val created = writes.create(this, roomId, userId, EntityType.Annotation, req.annotationId, Annotations, ::annotation) {
            it[Annotations.documentId] = f.documentId
            it[Annotations.versionId] = f.versionId
            it[Annotations.anchor] = AnnotationAnchor(first.page, kind, first.rect, first.ref, first.quote)
            it[Annotations.authorId] = userId
            it[Annotations.kind] = AnnotationKind.Comment.wireName
            it[Annotations.status] = AnnotationStatus.Open.wireName
            it[Annotations.body] = body
            it[Annotations.anchorLost] = false
        }
        writes.update(this, roomId, userId, EntityType.AiFinding, id, AiFindings) {
            it[AiFindings.status] = FindingStatus.Converted.wireName
            it[AiFindings.convertedAnnotationId] = req.annotationId
            it[AiFindings.resolvedBy] = userId
        }
        created
    }

    // ── 批注与讨论 ──

    suspend fun createAnnotation(userId: UUID, roomId: UUID, documentId: UUID, req: CreateAnnotationRequest): Pair<Annotation, Boolean> {
        val body = cleanBody(req.body, Limits.ANNOTATION_BODY_LENGTH)
        val anchor = req.anchor.copy(quote = req.anchor.quote?.trim()?.take(Limits.ANCHOR_QUOTE_MAX)?.ifEmpty { null })
        validateAnchor(anchor)
        return db.tx {
            rooms.requireMember(roomId, userId)
            liveDocument(roomId, documentId)
            annotation(req.id)?.let { existing ->
                if (existing.roomId != roomId || existing.authorId != userId || existing.documentId != documentId) {
                    throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                }
                return@tx existing to false
            }
            val v = version(req.versionId)?.takeIf { it.documentId == documentId && it.deletedAt == null } ?: notFound()
            validate {
                check(v.previewStatus == PreviewStatus.Ready, "versionId", "预览还没生成好")
                check(anchor.page <= (v.pageCount ?: 0), "anchor", "没有这一页")
            }
            writes.create(this, roomId, userId, EntityType.Annotation, req.id, Annotations, ::annotation) {
                it[Annotations.documentId] = documentId
                it[Annotations.versionId] = v.id
                it[Annotations.anchor] = anchor
                it[Annotations.authorId] = userId
                it[Annotations.kind] = req.kind.wireName
                it[Annotations.status] = AnnotationStatus.Open.wireName
                it[Annotations.body] = body
                it[Annotations.anchorLost] = false
            }
        }
    }

    private fun validateAnchor(a: AnnotationAnchor) = validate {
        check(a.page >= 1, "anchor", "页码从 1 开始")
        val r = a.rect
        check(r == null || (r.x in 0.0..1.0 && r.y in 0.0..1.0 && r.w > 0 && r.h > 0 && r.x + r.w <= 1.0001 && r.y + r.h <= 1.0001), "anchor", "位置不对")
        check(a.ref == null || a.ref!!.length in 1..32, "anchor", "位置不对")
        when (a.kind) {
            AnchorKind.Region, AnchorKind.Image -> check(r != null, "anchor", "要圈出一块区域")
            AnchorKind.Paragraph, AnchorKind.Cell -> check(a.ref != null, "anchor", "要选中一段文字或一个单元格")
            AnchorKind.Slide -> Unit
        }
    }

    private fun liveAnnotation(roomId: UUID, documentId: UUID?, id: UUID): Annotation {
        val a = annotation(id)?.takeIf { it.roomId == roomId && it.deletedAt == null && (documentId == null || it.documentId == documentId) } ?: notFound()
        liveDocument(roomId, a.documentId)
        return a
    }

    suspend fun updateAnnotation(userId: UUID, roomId: UUID, documentId: UUID, id: UUID, req: UpdateAnnotationRequest): Annotation {
        val body = (req.body as? Patch.Value)?.value?.let { cleanBody(it, Limits.ANNOTATION_BODY_LENGTH) }
        val status = (req.status as? Patch.Value)?.value
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = liveAnnotation(roomId, documentId, id)
            if (body != null && body != current.body && current.authorId != userId) forbidden("只能改自己写的批注")
            val bodyChanged = body != null && body != current.body
            val statusChanged = status != null && status != current.status
            if (bodyChanged || statusChanged) {
                writes.update(this, roomId, userId, EntityType.Annotation, id, Annotations) {
                    if (bodyChanged) it[Annotations.body] = body!!
                    if (statusChanged) {
                        it[Annotations.status] = status!!.wireName
                        val resolved = status != AnnotationStatus.Open
                        it[Annotations.resolvedBy] = if (resolved) userId else null
                        it[Annotations.resolvedAt] = if (resolved) clock.instant() else null
                    }
                }
            }
            annotation(id)!!
        }
    }

    suspend fun deleteAnnotation(userId: UUID, roomId: UUID, documentId: UUID, id: UUID): Annotation = db.tx {
        rooms.requireMember(roomId, userId)
        val current = liveAnnotation(roomId, documentId, id)
        if (current.authorId != userId) forbidden("只能删自己写的批注")
        writes.softDelete(this, roomId, userId, EntityType.Annotation, id, Annotations)
        annotation(id)!!
    }

    suspend fun createReply(userId: UUID, roomId: UUID, annotationId: UUID, req: CreateAnnotationReplyRequest): Pair<AnnotationReply, Boolean> {
        val body = cleanBody(req.body, Limits.ANNOTATION_REPLY_LENGTH)
        return db.tx {
            rooms.requireMember(roomId, userId)
            liveAnnotation(roomId, null, annotationId)
            val result = writes.create(this, roomId, userId, EntityType.AnnotationReply, req.id, AnnotationReplies, ::reply) {
                it[AnnotationReplies.annotationId] = annotationId
                it[AnnotationReplies.authorId] = userId
                it[AnnotationReplies.body] = body
            }
            if (!result.second && (result.first.authorId != userId || result.first.annotationId != annotationId)) {
                throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            }
            result
        }
    }

    /**
     * 彻底删除前（回收站调用，事务内）：删掉各版本的预览页，返回要一起删的文件 id（原文件和页面图片）。
     * 调用方删完版本之后再用 [FileService.releaseAll] 删文件记录。
     */
    fun detachFiles(documentId: UUID): List<UUID> {
        val versions = ReviewVersions.select(ReviewVersions.id, ReviewVersions.fileId).where { ReviewVersions.documentId eq documentId }
            .map { it[ReviewVersions.id] to it[ReviewVersions.fileId] }
        val fileIds = mutableListOf<UUID>()
        for ((v, original) in versions) {
            fileIds += ReviewPages.select(ReviewPages.imageFileId).where { ReviewPages.versionId eq v }.map { it[ReviewPages.imageFileId] }
            ReviewPages.deleteWhere { ReviewPages.versionId eq v }
            fileIds += original
        }
        return fileIds.distinct()
    }

    companion object {
        const val JOB_PREVIEW = "review_preview"

        /** 对比时把一块里的换行、连续空白都压成一个空格。 */
        fun normalize(text: String) = text.replace(Regex("\\s+"), " ").trim()
    }
}

/** 在新版本里重新找到批注的位置。 */
object Relocate {
    /** 返回新位置和「是否没找到」。 */
    fun anchor(old: AnnotationAnchor, pages: List<ReviewPage>): Pair<AnnotationAnchor, Boolean> {
        val lastPage = pages.maxOf { it.page }
        val fallback = old.copy(page = old.page.coerceAtMost(lastPage), ref = if (old.page <= lastPage) old.ref else null)
        val quote = old.quote?.let { ReviewService.normalize(it) }?.takeIf { it.isNotEmpty() }
        if (quote == null) {
            // 没有原文摘录（圈的区域、图片）：原页码还在就照旧，否则算没找到
            val lost = old.page > lastPage || old.kind == AnchorKind.Paragraph || old.kind == AnchorKind.Cell
            return fallback.copy(ref = null) to lost
        }
        var best: Pair<ReviewPage, TextBlock>? = null
        var bestScore = 0.0
        for (page in pages) {
            for (block in page.blocks) {
                val text = ReviewService.normalize(block.text)
                var score = when {
                    text == quote -> 1.0
                    text.contains(quote) || (text.length >= 8 && quote.contains(text)) -> 0.9
                    else -> similarity(text, quote)
                }
                // 同样好时，离原来的页近的优先
                score -= 0.001 * kotlin.math.abs(page.page - old.page)
                if (score > bestScore) {
                    bestScore = score
                    best = page to block
                }
            }
        }
        val found = best
        if (found == null || bestScore < 0.6) return fallback.copy(ref = null) to true
        val (page, block) = found
        val kind = when (old.kind) {
            AnchorKind.Paragraph, AnchorKind.Cell -> block.kind
            else -> old.kind
        }
        val rect = when (old.kind) {
            AnchorKind.Region, AnchorKind.Image -> old.rect
            AnchorKind.Slide -> null
            else -> block.rect
        }
        val ref = if (kind == AnchorKind.Paragraph || kind == AnchorKind.Cell) block.id else null
        return AnnotationAnchor(page.page, kind, rect, ref, old.quote) to false
    }

    /** 字符二元组的 Dice 相似度（0–1），对中文也管用。 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val grams = HashMap<String, Int>()
        for (i in 0 until a.length - 1) grams.merge(a.substring(i, i + 2), 1, Int::plus)
        var hits = 0
        for (i in 0 until b.length - 1) {
            val g = b.substring(i, i + 2)
            val n = grams[g] ?: 0
            if (n > 0) {
                hits++
                grams[g] = n - 1
            }
        }
        return 2.0 * hits / (a.length - 1 + b.length - 1)
    }
}
