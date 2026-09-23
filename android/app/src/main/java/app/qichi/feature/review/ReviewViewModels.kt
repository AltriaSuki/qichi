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
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.sync.Local
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.ReviewDiff
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
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

/** 一条 AI 发现：[carriedFrom] 是从 v 几带过来的。 */
data class FindingItem(val local: Local<AiFinding>, val carriedFrom: Int?) {
    val value: AiFinding get() = local.value
}

/** 审稿 AI 的状态：在不在等结果、上次有没有失败、AI 开没开、有没有网。 */
data class AiState(val pending: UUID? = null, val failed: String? = null, val enabled: Boolean = false, val online: Boolean = true, val lastCount: Int? = null)

data class ReviewState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val doc: ReviewDocument? = null,
    val versions: List<ReviewVersion> = emptyList(),
    val version: ReviewVersion? = null,
    val annotations: List<AnnotationItem> = emptyList(),
    val findings: List<FindingItem> = emptyList(),
    val ai: AiState = AiState(),
    val loaded: Boolean = false,
) {
    val newFindings: List<FindingItem> get() = findings.filter { it.value.status == FindingStatus.New }
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
    network: NetworkMonitor,
    realtime: RealtimeClient,
) : ViewModel() {
    val me: UUID? = session.currentUserId
    private val ai = MutableStateFlow(AiState())
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    /** 选中的版本号；为空 = 最新的 */
    private val selected = MutableStateFlow<Int?>(null)

    val state: StateFlow<ReviewState> = combine(
        people, reviews.observeDocuments(roomId), reviews.observeVersions(roomId),
        combine(reviews.observeAnnotations(roomId), reviews.observeReplies(roomId), selected, reviews.observeFindings(roomId),
            combine(ai, network.isOnline, rooms.me) { a, online, me -> a.copy(online = online, enabled = me?.aiEnabled == true) }) { a, r, s, f, aiState ->
            Env(a, r, s, f, aiState)
        },
    ) { p, docs, allVersions, env ->
        val (anns, replies, sel) = Triple(env.anns, env.replies, env.selected)
        val doc = docs.firstOrNull { it.id == docId }
        val versions = allVersions.filter { it.documentId == docId }.sortedBy { it.version }
        val version = versions.firstOrNull { it.version == sel } ?: versions.lastOrNull()
        ReviewState(
            people = p, zone = zoneOf(p.room?.timezone), doc = doc, versions = versions, version = version,
            annotations = if (version == null) emptyList() else annotationItems(anns, replies, versions, version.id),
            findings = if (version == null) emptyList() else {
                val byId = env.findings.associateBy { it.value.id }
                val versionNo = versions.associate { it.id to it.version }
                env.findings.filter { it.value.versionId == version.id && it.value.deletedAt == null }
                    .sortedBy { it.value.createdAt }
                    .map { f -> FindingItem(f, f.value.carriedFromId?.let { byId[it]?.value?.versionId }?.let { versionNo[it] }) }
            },
            ai = env.ai,
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
        // 等的 AI 审稿有结果了（实时通道通知；万一漏了，每 5 秒问一次）
        viewModelScope.launch {
            realtime.aiDone.collect { e ->
                if (e.jobId == ai.value.pending) finishAi(e.status == AiJobStatus.Done.wireName)
            }
        }
        viewModelScope.launch {
            ai.map { it.pending }.distinctUntilChanged().collectLatest { jobId ->
                jobId ?: return@collectLatest
                repeat(60) {
                    delay(5_000)
                    val job = runCatching { reviews.aiJob(roomId, jobId) }.getOrNull() ?: return@repeat
                    if (job.status == AiJobStatus.Done || job.status == AiJobStatus.Failed) {
                        finishAi(job.status == AiJobStatus.Done, job.error)
                        return@collectLatest
                    }
                }
            }
        }
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

    /** 本次授权 AI 审当前这一版。返回不能开始的原因（没有就是开始了）。 */
    fun askAi(): String? {
        val s = state.value
        val doc = s.doc ?: return null
        val version = s.version ?: return null
        when {
            !s.ai.online -> return "需要联网"
            !s.ai.enabled -> return "AI 还没有开启（要在服务器上配置）"
            s.ai.pending != null -> return "AI 还在看"
            version.previewStatus != PreviewStatus.Ready -> return "预览生成好之后才能请 AI 看"
        }
        val jobId = UuidV7.generate()
        val before = s.findings.size
        ai.value = AiState(pending = jobId, lastCount = before)
        viewModelScope.launch {
            try {
                reviews.askAi(doc, version, jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = when ((e as? ApiException)?.code) {
                    ProblemCode.AiQuotaExceeded -> "这个月的 AI 额度用完了"
                    ProblemCode.AiUnavailable -> "AI 还没有开启"
                    else -> "没能发给 AI，稍后再试"
                }
                ai.update { if (it.pending == jobId) it.copy(pending = null, failed = reason) else it }
            }
        }
        return null
    }

    private fun finishAi(done: Boolean, error: String? = null) {
        val before = ai.value.lastCount ?: 0
        ai.update { it.copy(pending = null, failed = if (done) null else (error ?: "AI 没有给出结果")) }
        if (done) viewModelScope.launch {
            // 等同步把新的发现带回来再说有几条
            delay(1_500)
            val added = state.value.findings.size - before
            _message.value = if (added > 0) "AI 找到 $added 个值得看的地方" else "AI 没发现明显的问题"
        }
    }

    fun dismiss(f: AiFinding) = viewModelScope.launch { reviews.dismiss(f) }
    fun convert(f: AiFinding) = viewModelScope.launch {
        reviews.convert(f)
        _message.value = "转成批注了"
    }

    fun closeDiff() { _diff.value = null }
    fun messageShown() { _message.value = null }

    @AssistedFactory
    interface Factory { fun create(@Assisted("room") roomId: UUID, @Assisted("doc") docId: UUID): ReviewViewModel }
}

private data class Env(
    val anns: List<Local<Annotation>>,
    val replies: List<Local<AnnotationReply>>,
    val selected: Int?,
    val findings: List<Local<AiFinding>>,
    val ai: AiState,
)

data class DiffState(val from: Int, val to: Int, val diff: ReviewDiff?, val error: String?)
