package app.qichi.shared.api

import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ReviewFormat
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 审稿（P7；openapi.yaml：reviews）──

/** 页面上的一块矩形，按页面宽高的比例（0–1），左上角为原点。 */
@Serializable
data class NormRect(val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * 批注钉在哪儿。[page] 从 1 开始；[ref] 是文字层里的块（[TextBlock.id]），kind = paragraph / cell 时有；
 * [rect] 是位置（region / image 必有，其它可选，方便画圈）；[quote] 是当时那块的原文摘录，用来在新版本里重新找到位置。
 */
@Serializable
data class AnnotationAnchor(
    val page: Int,
    val kind: AnchorKind,
    val rect: NormRect? = null,
    val ref: String? = null,
    val quote: String? = null,
)

/** 同步实体 review_document：一份要审的文件，下面有一个个不可变的版本。 */
@Serializable
data class ReviewDocument(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val title: String,
    val createdBy: Id,
    /** 最新的版本号（从 1 开始） */
    val latestVersion: Int,
) : SyncEntity

/**
 * 同步实体 review_version：一个版本。原文件不可变；预览（按页的图片和文字层）在后台生成，
 * 生成好后 [previewStatus] = ready、[pageCount] 有值。
 */
@Serializable
data class ReviewVersion(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val documentId: Id,
    val version: Int,
    val fileId: Id,
    val fileName: String,
    val format: ReviewFormat,
    val uploadedBy: Id,
    val previewStatus: PreviewStatus,
    val pageCount: Int?,
    /** 预览失败时给人看的原因 */
    val previewError: String?,
) : SyncEntity

/**
 * 同步实体 annotation：批注或修改提议，钉在某个版本的某个位置。
 * 新版本的预览生成后，上一版里还没处理完（open）的批注会带到新版本（新的一条，[carriedFromId] 指向旧的），
 * 讨论沿着 carriedFromId 一路往回看。
 */
@Serializable
data class Annotation(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val documentId: Id,
    val versionId: Id,
    val anchor: AnnotationAnchor,
    val authorId: Id,
    val kind: AnnotationKind,
    val status: AnnotationStatus,
    val body: String,
    val carriedFromId: Id?,
    /** 带到新版本时没找到原来的位置（钉在原页码上，请人再看一眼） */
    val anchorLost: Boolean,
    /** 谁接受或归档的 */
    val resolvedBy: Id?,
    val resolvedAt: Timestamp?,
) : SyncEntity

/** 同步实体 annotation_reply：批注下面的讨论。 */
@Serializable
data class AnnotationReply(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val annotationId: Id,
    val authorId: Id,
    val body: String,
) : SyncEntity

/** 文字层里的一块：一段文字或一个单元格，[id] 在这个版本里唯一（如 p2-b5）。 */
@Serializable
data class TextBlock(
    val id: String,
    val kind: AnchorKind,
    val rect: NormRect,
    val text: String,
)

/** 预览的一页（不走同步，按需取）：渲染好的图片、文字层、图片区域。[width]、[height] 是页面尺寸（pt）。 */
@Serializable
data class ReviewPage(
    val page: Int,
    val width: Double,
    val height: Double,
    val imageFileId: Id,
    val blocks: List<TextBlock>,
    val images: List<NormRect>,
)

/** 版本间文字差异的一行：一块文字，带它在旧 / 新版本里的页码。 */
@Serializable
data class ReviewDiffLine(
    val kind: DiffKind,
    val text: String,
    val oldPage: Int?,
    val newPage: Int?,
)

@Serializable
enum class DiffKind {
    @SerialName("same") Same,
    @SerialName("removed") Removed,
    @SerialName("added") Added,
}

@Serializable
data class ReviewDiff(val from: Int, val to: Int, val lines: List<ReviewDiffLine>)

/** 新版本：先把文件传到 /files（kind = review），再用它的 id 建版本。 */
@Serializable
data class CreateReviewVersionRequest(val id: Id, val fileId: Id)

/** 新建审稿文件，同时带上第一个版本。 */
@Serializable
data class CreateReviewRequest(val id: Id, val title: String, val firstVersion: CreateReviewVersionRequest)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class UpdateReviewRequest(@EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent)

@Serializable
data class CreateAnnotationRequest(
    val id: Id,
    val versionId: Id,
    val anchor: AnnotationAnchor,
    val kind: AnnotationKind,
    val body: String,
)

/** 正文只有作者能改；状态两个人都能改（接受、归档、重新打开）。 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class UpdateAnnotationRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val body: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val status: Patch<AnnotationStatus> = Patch.Absent,
)

@Serializable
data class CreateAnnotationReplyRequest(val id: Id, val body: String)

// ── 审稿 AI（P7-03） ──

/** 一条原文证据：原文摘录和它在哪（第几页、文字层里的哪一块）。 */
@Serializable
data class FindingEvidence(
    val page: Int,
    val ref: String,
    val quote: String,
    val rect: NormRect,
)

/**
 * 同步实体 ai_finding：AI 审稿时发现的一个问题，一定带原文证据和定位。AI 只提出问题，不替人定稿：
 * 两人决定忽略它，或者转成一条人工批注。新版本的预览生成后，还是「新的」发现如果原文还在就带过去（[carriedFromId]），
 * 原文找不到了就在旧的上面记下 [goneInVersion]（可能已经改好了）。
 */
@Serializable
data class AiFinding(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val documentId: Id,
    val versionId: Id,
    /** 哪次 AI 审稿产生的（带过来的沿用原来的） */
    val jobId: Id,
    val requestedBy: Id?,
    val title: String,
    val body: String,
    val evidence: List<FindingEvidence>,
    val status: FindingStatus,
    val convertedAnnotationId: Id?,
    val carriedFromId: Id?,
    /** 在这个版本里找不到证据原文了（可能已经改好） */
    val goneInVersion: Int?,
    val resolvedBy: Id?,
) : SyncEntity

/** 本次授权 AI 审这个版本 → 202；结果是若干条 ai_finding（jobId = 这里的 jobId）。 */
@Serializable
data class AiReviewFindingsRequest(val jobId: Id, val documentId: Id, val versionId: Id)

/** 把发现转成人工批注：批注 id 由客户端生成（离线补发不重复）。 */
@Serializable
data class ConvertFindingRequest(val annotationId: Id)
