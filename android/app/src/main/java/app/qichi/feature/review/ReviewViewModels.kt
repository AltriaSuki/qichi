package app.qichi.feature.review

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.ReviewRepository
import app.qichi.core.data.ReviewUploadException
import app.qichi.core.data.RoomRepository
import app.qichi.core.network.ApiException
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.Local
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.ReviewDiff
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ProblemCode
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/** 上传失败时给人看的一句话。 */
internal fun uploadError(e: Exception): String = when {
    e is ReviewUploadException -> e.message ?: "没能上传"
    e is ApiException && e.code == ProblemCode.UnsupportedMediaType -> "不支持这种文件，可以传 PDF、Word、Excel、PowerPoint"
    e is ApiException && e.code == ProblemCode.PayloadTooLarge -> "文件太大了（不能超过 100MB）"
    else -> "没能上传，传文件需要联网"
}

// ───────────────────────── 列表 ─────────────────────────

data class ReviewItem(val doc: ReviewDocument, val latest: ReviewVersion?, val open: Int)

data class ReviewListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val items: List<ReviewItem> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = ReviewListViewModel.Factory::class)
class ReviewListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val reviews: ReviewRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    val state: StateFlow<ReviewListState> = combine(people, reviews.observeDocuments(roomId), reviews.observeVersions(roomId), reviews.observeAnnotations(roomId)) { p, docs, versions, anns ->
        ReviewListState(
            people = p, zone = zoneOf(p.room?.timezone),
            items = docs.map { d ->
                val latest = versions.filter { it.documentId == d.id }.maxByOrNull { it.version }
                ReviewItem(d, latest, anns.count { it.value.versionId == latest?.id && it.value.deletedAt == null && it.value.status == AnnotationStatus.Open })
            },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewListState())

    private val _adding = MutableStateFlow<Float?>(null)
    val adding: StateFlow<Float?> = _adding
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _opened = MutableStateFlow<UUID?>(null)
    /** 新建好之后打开它 */
    val opened: StateFlow<UUID?> = _opened

    fun add(uri: Uri) = viewModelScope.launch {
        _adding.value = 0f
        try {
            _opened.value = reviews.create(roomId, uri) { _adding.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = uploadError(e)
        } finally {
            _adding.value = null
        }
    }

    fun delete(doc: ReviewDocument) = viewModelScope.launch { reviews.delete(doc) }

    fun rename(doc: ReviewDocument, title: String) = viewModelScope.launch { reviews.rename(doc, title) }

    fun openedHandled() { _opened.value = null }

    fun messageShown() { _message.value = null }

    @AssistedFactory
    interface Factory { fun create(roomId: UUID): ReviewListViewModel }
}

// ───────────────────────── 一份审稿 ─────────────────────────

/**
 * 某个版本的批注列表：按页、按上下位置编号；讨论沿着 carriedFromId 往回看（带过来的批注，前面版本里的讨论也算上）。
 */
internal fun annotationItems(
    anns: List<Local<Annotation>>,
    replies: List<Local<AnnotationReply>>,
    versions: List<ReviewVersion>,
    versionId: UUID,
): List<AnnotationItem> {
    val byId = anns.associateBy { it.value.id }
    val versionNo = versions.associate { it.id to it.version }
    val repliesBy = replies.map { it.value }.filter { it.deletedAt == null }.groupBy { it.annotationId }
    return anns.filter { it.value.versionId == versionId && it.value.deletedAt == null }
        .sortedWith(compareBy({ it.value.anchor.page }, { it.value.anchor.rect?.y ?: 0.0 }, { it.value.createdAt }))
        .mapIndexed { i, a ->
            val chain = generateSequence(a.value) { cur -> cur.carriedFromId?.let { byId[it]?.value } }.take(100).toList()
            AnnotationItem(
                a, i + 1,
                chain.flatMap { repliesBy[it.id].orEmpty() }.sortedBy { it.createdAt },
                a.value.carriedFromId?.let { byId[it]?.value?.versionId }?.let { versionNo[it] },
            )
        }
}

/** 一条批注在列表里的样子：[number] 是这个版本里按页、按位置排的序号。 */
data class AnnotationItem(
    val local: Local<Annotation>,
    val number: Int,
    val replies: List<AnnotationReply>,
    /** 从哪个版本带过来的（v 几），自己这版写的为空 */
    val carriedFrom: Int?,
) {
    val value: Annotation get() = local.value
}

sealed interface PagesState {
    data object Loading : PagesState
    data class Ready(val pages: List<ReviewPage>) : PagesState
    data class Error(val message: String) : PagesState
}

data class ReviewState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val doc: ReviewDocument? = null,
    val versions: List<ReviewVersion> = emptyList(),
    val version: ReviewVersion? = null,
    val annotations: List<AnnotationItem> = emptyList(),
    val loaded: Boolean = false,
) {
    val open: List<AnnotationItem> get() = annotations.filter { it.value.status == AnnotationStatus.Open }
    val resolved: List<AnnotationItem> get() = annotations.filter { it.value.status != AnnotationStatus.Open }
}

@HiltViewModel(assistedFactory = ReviewViewModel.Factory::class)
class ReviewViewModel @AssistedInject constructor(
    @Assisted("room") private val roomId: UUID,
    @Assisted("doc") private val docId: UUID,
    private val reviews: ReviewRepository,
    val urls: FileUrls,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    val me: UUID? = session.currentUserId
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    /** 选中的版本号；为空 = 最新的 */
    private val selected = MutableStateFlow<Int?>(null)

    val state: StateFlow<ReviewState> = combine(
        people, reviews.observeDocuments(roomId), reviews.observeVersions(roomId),
        combine(reviews.observeAnnotations(roomId), reviews.observeReplies(roomId), selected) { a, r, s -> Triple(a, r, s) },
    ) { p, docs, allVersions, (anns, replies, sel) ->
        val doc = docs.firstOrNull { it.id == docId }
        val versions = allVersions.filter { it.documentId == docId }.sortedBy { it.version }
        val version = versions.firstOrNull { it.version == sel } ?: versions.lastOrNull()
        ReviewState(
            people = p, zone = zoneOf(p.room?.timezone), doc = doc, versions = versions, version = version,
            annotations = if (version == null) emptyList() else annotationItems(anns, replies, versions, version.id),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    private val _pages = MutableStateFlow<PagesState>(PagesState.Loading)
    val pages: StateFlow<PagesState> = _pages
    private var pagesJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _uploading = MutableStateFlow<Float?>(null)
    val uploading: StateFlow<Float?> = _uploading

    private val _diff = MutableStateFlow<DiffState?>(null)
    val diff: StateFlow<DiffState?> = _diff

    init {
        // 版本换了、或者它的预览刚生成好：重新取预览页
        viewModelScope.launch {
            state.map { s -> s.doc to s.version }.distinctUntilChanged { a, b ->
                a.first?.id == b.first?.id && a.second?.id == b.second?.id && a.second?.previewStatus == b.second?.previewStatus
            }.collect { (doc, version) -> loadPages(doc, version) }
        }
    }

    private fun loadPages(doc: ReviewDocument?, version: ReviewVersion?) {
        pagesJob?.cancel()
        if (doc == null || version == null) return
        when (version.previewStatus) {
            PreviewStatus.Pending -> { _pages.value = PagesState.Loading; return }
            PreviewStatus.Failed -> { _pages.value = PagesState.Error(version.previewError ?: "预览没能生成"); return }
            PreviewStatus.Ready -> Unit
        }
        pagesJob = viewModelScope.launch {
            if (_pages.value !is PagesState.Ready) _pages.value = PagesState.Loading
            _pages.value = try {
                PagesState.Ready(reviews.pages(doc, version))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PagesState.Error("预览要联网取一次，取过之后离线也能看")
            }
        }
    }

    fun retryPages() = loadPages(state.value.doc, state.value.version)

    fun selectVersion(number: Int) {
        selected.value = number
    }

    fun uploadVersion(uri: Uri) = viewModelScope.launch {
        val doc = state.value.doc ?: return@launch
        _uploading.value = 0f
        try {
            val v = reviews.uploadVersion(doc, uri) { _uploading.value = it }
            selected.value = v.version
            _message.value = "传上去了，预览生成好后上一版没处理完的批注会带过来"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = uploadError(e)
        } finally {
            _uploading.value = null
        }
    }

    fun annotate(anchor: AnnotationAnchor, kind: AnnotationKind, body: String) = viewModelScope.launch {
        val s = state.value
        reviews.annotate(s.doc ?: return@launch, s.version ?: return@launch, anchor, kind, body)
    }

    fun edit(a: Annotation, body: String) = viewModelScope.launch { reviews.editAnnotation(a, body) }
    fun setStatus(a: Annotation, status: AnnotationStatus) = viewModelScope.launch { reviews.setStatus(a, status) }
    fun delete(a: Annotation) = viewModelScope.launch { reviews.deleteAnnotation(a) }
    fun reply(a: Annotation, body: String) = viewModelScope.launch { reviews.reply(a, body) }
    fun rename(title: String) = viewModelScope.launch { state.value.doc?.let { reviews.rename(it, title) } }
    fun deleteDocument() = viewModelScope.launch { state.value.doc?.let { reviews.delete(it) } }

    fun compare(from: Int, to: Int) = viewModelScope.launch {
        val doc = state.value.doc ?: return@launch
        _diff.value = DiffState(from, to, null, null)
        _diff.value = try {
            DiffState(from, to, reviews.diff(doc, from, to), null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DiffState(from, to, null, "对比要联网")
        }
    }

    fun closeDiff() { _diff.value = null }
    fun messageShown() { _message.value = null }

    @AssistedFactory
    interface Factory { fun create(@Assisted("room") roomId: UUID, @Assisted("doc") docId: UUID): ReviewViewModel }
}

data class DiffState(val from: Int, val to: Int, val diff: ReviewDiff?, val error: String?)
