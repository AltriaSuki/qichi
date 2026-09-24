package app.qichi.feature.writing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.DocumentRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.WritingSettings
import app.qichi.core.data.WritingSettingsStore
import app.qichi.core.database.DocumentVersionRow
import app.qichi.core.database.DraftRow
import app.qichi.core.network.NetworkMonitor
import app.qichi.shared.model.DraftGenre
import app.qichi.core.network.ApiException
import app.qichi.shared.model.WriteAssistMode
import app.qichi.shared.api.AiWriteRequest
import app.qichi.shared.api.DocComment
import app.qichi.shared.util.UuidV7
import app.qichi.core.network.FileUrls
import app.qichi.core.data.FileRepository
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.data.AttachmentException
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Document
import app.qichi.shared.util.CjkText
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// ───────────────────────── 列表 ─────────────────────────

data class DocumentListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val documents: List<Local<Document>> = emptyList(),
    /** 有未保存内容的文稿 */
    val unsaved: Set<UUID> = emptySet(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = DocumentListViewModel.Factory::class)
class DocumentListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val docs: DocumentRepository,
    rooms: RoomRepository,
    session: SessionManager,
    private val network: NetworkMonitor,
) : ViewModel() {
    // ── AI 起草稿（P9-05）：草稿先给人看，点「用它开始写」才建文稿，内容作为未保存的草稿 ──
    private val _draft = MutableStateFlow<DraftRequest?>(null)
    val draft: StateFlow<DraftRequest?> = _draft
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var draftJob: kotlinx.coroutines.Job? = null

    fun messageShown() { _message.value = null }

    fun requestDraft(genre: DraftGenre, range: DraftRanges.Range) {
        if (!network.isOnline.value) {
            _message.value = "离线时不能请 AI 起草稿"
            return
        }
        draftJob?.cancel()
        _draft.value = DraftRequest(genre, range, result = null)
        draftJob = viewModelScope.launch {
            try {
                val text = docs.assist(roomId, AiWriteRequest(UuidV7.generate(), WriteAssistMode.Draft, genre = genre, rangeStart = range.start, rangeEnd = range.end))
                _draft.value = _draft.value?.copy(result = text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _draft.value = null
                _message.value = e.userMessage
            } catch (e: Exception) {
                _draft.value = null
                _message.value = (e as? DocumentRepository.AssistFailed)?.message ?: "AI 没有给出草稿，再试一次"
            }
        }
    }

    fun dismissDraft() {
        draftJob?.cancel()
        _draft.value = null
    }

    /** 用草稿开始写：建一篇文稿（标题取草稿第一行的「# 标题」），草稿作为还没保存的内容。 */
    fun useDraft(onCreated: (UUID) -> Unit) = viewModelScope.launch {
        val d = _draft.value ?: return@launch
        val text = d.result ?: return@launch
        val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.ifEmpty { null } ?: d.genre.defaultTitle()
        val doc = docs.create(roomId, title) ?: return@launch
        docs.writeDraft(roomId, doc.id, text, baseVersion = 0, baseBody = "")
        _draft.value = null
        onCreated(doc.id)
    }

    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    val state: StateFlow<DocumentListState> = combine(people, docs.observeDocuments(roomId), docs.observeDraftIds(roomId)) { p, list, drafts ->
        val zone = zoneOf(p.room?.timezone)
        DocumentListState(p, zone, todayIn(zone), list, drafts, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentListState())

    fun create(title: String, onCreated: (UUID) -> Unit) = viewModelScope.launch {
        docs.create(roomId, title)?.let { onCreated(it.id) }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): DocumentListViewModel
    }
}

// ───────────────────────── 编辑器 ─────────────────────────

data class EditorState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** 还没读出来，或已经删除时为空 */
    val document: Local<Document>? = null,
    val loaded: Boolean = false,
    /** 编辑框里的内容 */
    val text: String = "",
    /** 内容是否已经可以编辑（最新版本的正文取到了，或本来就有草稿） */
    val ready: Boolean = false,
    /** 最新版本的正文没缓存、又离线：先不能编辑 */
    val needsNetwork: Boolean = false,
    /** 编辑框里的内容依据的版本 */
    val baseVersion: Int = 0,
    /** 有没保存的内容 */
    val unsaved: Boolean = false,
    /** 保存正在发出 */
    val saving: Boolean = false,
    /** 对方存了更新的版本，而自己还有没保存的内容：要先重基线 */
    val conflict: Boolean = false,
    val latestBody: String? = null,
    val settings: WritingSettings = WritingSettings(),
) {
    val charCount: Int get() = CjkText.charCount(text)
    val minutes: Int get() = CjkText.readingMinutes(charCount)
    val latestVersion: Int get() = document?.value?.latestVersion ?: 0
    val canSave: Boolean get() = ready && unsaved && !saving && !conflict
}

/** 编辑器里的一次输入：只保留最新的，依次写进本机草稿。 */
private data class Edit(val text: String, val baseVersion: Int, val baseBody: String?)

@OptIn(ExperimentalCoroutinesApi::class)
/** 一次 AI 起草稿：[result] 为空时还在等。 */
data class DraftRequest(val genre: DraftGenre, val range: DraftRanges.Range, val result: String?)

internal fun DraftGenre.label() = when (this) {
    DraftGenre.Travel -> "游记"
    DraftGenre.Letter -> "写给对方的信"
    DraftGenre.Review -> "回顾"
}

internal fun DraftGenre.defaultTitle() = when (this) {
    DraftGenre.Travel -> "游记"
    DraftGenre.Letter -> "一封信"
    DraftGenre.Review -> "回顾"
}

/** 写作助手的一次请求：[result] 为空时还在等。 */
data class AssistState(val mode: WriteAssistMode, val original: String, val start: Int, val end: Int, val result: String?)

@HiltViewModel(assistedFactory = DocumentEditorViewModel.Factory::class)
class DocumentEditorViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("documentId") private val documentId: UUID,
    private val docs: DocumentRepository,
    private val settingsStore: WritingSettingsStore,
    rooms: RoomRepository,
    private val network: NetworkMonitor,
    session: SessionManager,
    private val preparer: AttachmentPreparer,
    private val files: FileRepository,
    val urls: FileUrls,
) : ViewModel() {
    // ── 插照片（P9-02，要联网） ──
    private val _uploadingImage = MutableStateFlow(false)
    val uploadingImage: StateFlow<Boolean> = _uploadingImage
    private val _message = MutableStateFlow<String?>(null)

    /** 一句要告诉人的话（插照片失败等），显示后调用 [messageShown] */
    val message: StateFlow<String?> = _message

    fun messageShown() { _message.value = null }

    /** 上传选中的照片，成功后把文件 id 交给 [onUploaded]（由编辑器插进光标处）。 */
    fun uploadImage(uri: android.net.Uri, onUploaded: (UUID) -> Unit) {
        if (!network.isOnline.value) {
            _message.value = "离线时不能插照片"
            return
        }
        if (_uploadingImage.value) return
        _uploadingImage.value = true
        viewModelScope.launch {
            try {
                val prepared = preparer.image(uri)
                val meta = files.upload(roomId, UuidV7.generate(), prepared)
                onUploaded(meta.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentException) {
                _message.value = e.message
            } catch (_: Exception) {
                _message.value = "照片没有传上去，再试一次"
            } finally {
                _uploadingImage.value = false
            }
        }
    }

    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val document = docs.observeDocument(roomId, documentId)

    // ── 写作助手（P9-04 / P9-05） ──
    private val _assist = MutableStateFlow<AssistState?>(null)

    /** 正在请 AI 帮忙 / AI 的建议（等人决定用不用）；null = 没有 */
    val assist: StateFlow<AssistState?> = _assist
    private var assistJob: kotlinx.coroutines.Job? = null

    /** 请 AI 处理 [text]（选区 [start]～[end]；起标题时是整篇）。 */
    fun requestAssist(mode: WriteAssistMode, text: String, start: Int, end: Int) {
        if (!network.isOnline.value) {
            _message.value = "离线时不能请 AI 帮忙"
            return
        }
        assistJob?.cancel()
        _assist.value = AssistState(mode, text, start, end, result = null)
        assistJob = viewModelScope.launch {
            try {
                val result = docs.assist(roomId, AiWriteRequest(UuidV7.generate(), mode, text, documentId))
                _assist.value = _assist.value?.copy(result = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _assist.value = null
                _message.value = e.userMessage
            } catch (e: Exception) {
                _assist.value = null
                _message.value = (e as? DocumentRepository.AssistFailed)?.message ?: "AI 没有给出结果，再试一次"
            }
        }
    }

    fun dismissAssist() {
        assistJob?.cancel()
        _assist.value = null
    }

    // ── 段落旁留言（P9-03） ──
    val comments: StateFlow<List<Local<DocComment>>> = docs.observeComments(roomId, documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addComment(quote: String, body: String) = viewModelScope.launch {
        state.value.document?.value?.let { docs.addComment(it, quote, body) }
    }

    fun reply(root: DocComment, body: String) = viewModelScope.launch { docs.reply(root, body) }

    fun setResolved(root: DocComment, resolved: Boolean) = viewModelScope.launch { docs.setResolved(root, resolved) }

    fun deleteComment(root: DocComment) = viewModelScope.launch { docs.deleteComment(root) }
    private val draft = docs.observeDraft(roomId, documentId)

    /** 最新版本的正文（本机缓存；没有就去取） */
    private val latestBody = document.map { it?.value?.latestVersion ?: 0 }.distinctUntilChanged().flatMapLatest { v ->
        if (v == 0) flowOf(LatestBody("", false)) else docs.observeVersion(documentId, v).map { row -> LatestBody(row?.body, false) }
    }
    private data class LatestBody(val body: String?, val failed: Boolean)
    private val fetchFailed = MutableStateFlow<Int?>(null)

    /** 编辑框里的内容：本机正在输入的优先（不被数据库的回写打断） */
    private val typed = MutableStateFlow<String?>(null)
    private val edits = Channel<Edit>(Channel.CONFLATED)

    private data class Sources(val people: People, val doc: Local<Document>?, val draft: DraftRow?, val latest: String?, val saving: Boolean)

    private val sources = combine(people, document, draft, latestBody, docs.observeSaving(documentId)) { p, d, dr, lb, s -> Sources(p, d, dr, lb.body, s) }

    val state: StateFlow<EditorState> = combine(sources, typed, fetchFailed, settingsStore.settings, network.isOnline) { src, t, failed, settings, online ->
        val doc = src.doc?.value
        val latestVersion = doc?.latestVersion ?: 0
        val base = src.draft?.baseVersion ?: latestVersion
        val text = t ?: src.draft?.text ?: src.latest ?: ""
        val ready = doc != null && (src.draft != null || src.latest != null)
        EditorState(
            people = src.people,
            zone = zoneOf(src.people.room?.timezone),
            document = src.doc,
            loaded = true,
            text = text,
            ready = ready,
            needsNetwork = doc != null && !ready && (!online || failed == latestVersion),
            baseVersion = base,
            unsaved = src.draft != null,
            saving = src.saving,
            conflict = src.draft != null && base < latestVersion && !src.saving,
            latestBody = src.latest,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditorState())

    init {
        // 依次把输入写进草稿
        viewModelScope.launch {
            for (edit in edits) docs.writeDraft(roomId, documentId, edit.text, edit.baseVersion, edit.baseBody)
        }
        // 最新版本的正文本机没有：取一次
        viewModelScope.launch {
            document.filterNotNull().map { it.value.latestVersion }.distinctUntilChanged().collect { v ->
                if (v > 0) fetchLatest(v)
            }
        }
        // 没有草稿时，输入框跟着最新版本走（对方保存了，这边直接看到）
        viewModelScope.launch {
            draft.distinctUntilChanged().collect { if (it == null) typed.value = null }
        }
    }

    private suspend fun fetchLatest(version: Int) {
        try {
            docs.loadBody(roomId, documentId, version)
            fetchFailed.update { if (it == version) null else it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fetchFailed.value = version
        }
    }

    /** 本机正在输入（编辑框是内容的来源，不要被数据库的回写覆盖）。 */
    fun hasLocalEdits(): Boolean = typed.value != null

    fun retryLoad() = viewModelScope.launch { fetchLatest(state.value.latestVersion) }

    fun onTextChange(text: String) {
        val s = state.value
        if (!s.ready) return
        typed.value = text
        val baseBody = if (s.baseVersion == s.latestVersion) s.latestBody else null
        edits.trySend(Edit(text, s.baseVersion, baseBody))
    }

    fun save() = viewModelScope.launch {
        val s = state.value
        val doc = s.document?.value ?: return@launch
        if (!s.canSave) return@launch
        // 等最后一次输入写进草稿再保存
        draft.first { it?.text == s.text }
        docs.save(doc)
    }

    /** 重基线：保留自己的内容，改为基于最新版本（之后可以正常保存）。 */
    fun keepMine() = viewModelScope.launch { docs.rebase(roomId, documentId, state.value.latestVersion) }

    /** 重基线：放弃自己没保存的内容，换成最新版本。 */
    fun takeLatest() = viewModelScope.launch {
        typed.value = null
        docs.discardDraft(roomId, documentId)
    }

    fun rename(title: String) = viewModelScope.launch { state.value.document?.value?.let { docs.rename(it, title) } }

    fun delete() = viewModelScope.launch { state.value.document?.value?.let { docs.delete(it) } }

    fun setSettings(settings: WritingSettings) = viewModelScope.launch { settingsStore.set(settings) }

    // ── 历史版本 ──

    val versions: StateFlow<List<DocumentVersionRow>> =
        docs.observeVersions(documentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 打开历史时在线补齐版本列表；离线就用本机已有的。 */
    fun refreshVersions() = viewModelScope.launch {
        try {
            docs.refreshVersions(roomId, documentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** 取某个版本和它前一个版本的正文，用来逐行对比；取不到返回 null。 */
    suspend fun bodies(version: Int): Pair<String, String>? = try {
        val current = docs.loadBody(roomId, documentId, version)
        val previous = if (version > 1) docs.loadBody(roomId, documentId, version - 1) else ""
        previous to current
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 旧版另存为新版。有没保存的内容时由界面先确认。 */
    fun restore(version: Int, body: String) = viewModelScope.launch {
        val doc = state.value.document?.value ?: return@launch
        typed.value = null
        docs.restore(doc, version, body)
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("documentId") documentId: UUID): DocumentEditorViewModel
    }
}
