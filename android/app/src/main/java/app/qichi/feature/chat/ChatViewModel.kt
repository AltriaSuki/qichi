package app.qichi.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.AttachmentException
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.data.ChatRepository
import app.qichi.core.data.People
import app.qichi.core.data.PreparedAttachment
import app.qichi.core.data.RoomRepository
import app.qichi.core.network.ApiException
import app.qichi.core.network.FileUrls
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.Local
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.util.UuidV7
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ChatState(
    val people: People = People.Empty,
    val online: Boolean = true,
)

/** 正在上传的附件（还没成为消息），显示在列表最下面。[id] 就是文件 id，重试时沿用。 */
data class Upload(
    val id: UUID,
    val attachment: PreparedAttachment,
    val progress: Float = 0f,
    val failed: String? = null,
)

/** 聊天搜索（服务端搜索，需要联网）。 */
data class SearchState(
    val open: Boolean = false,
    val query: String = "",
    val results: List<Message> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    /** 搜过至少一次（区分「还没搜」和「没搜到」） */
    val searched: Boolean = false,
    val error: String? = null,
)

/** 一次性的界面事件。 */
sealed interface ChatEvent {
    /** 附件已下载到本机，交给别的应用打开 */
    data class OpenFile(val file: File, val mimeType: String) : ChatEvent

    /** 滚到第 [index] 条并短暂高亮 [id] */
    data class ScrollTo(val index: Int, val id: UUID) : ChatEvent
    data class Toast(val text: String) : ChatEvent
}

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val chat: ChatRepository,
    private val preparer: AttachmentPreparer,
    val urls: FileUrls,
    rooms: RoomRepository,
    private val network: NetworkMonitor,
    @ApplicationContext private val context: Context,
    session: SessionManager,
) : ViewModel() {

    val messages: Flow<PagingData<Local<Message>>> = chat.messages(roomId).cachedIn(viewModelScope)

    val state: StateFlow<ChatState> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), network.isOnline) { room, members, online ->
        ChatState(People(room, members, session.currentUserId), online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    /** 最新一条消息的 id 与作者：界面据此决定跟到底部还是提示「新消息」 */
    val newest: StateFlow<Message?> = chat.observeNewest(roomId).map { it?.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 进入聊天时的未读位置（null = 还没从本机读出来） */
    val lastRead: StateFlow<Long?> = chat.observeLastRead(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val newestSeq: StateFlow<Long> = chat.observeNewestSeq(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 正在回复的消息（输入框上方显示） */
    private val _replyTo = MutableStateFlow<Message?>(null)
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatEvent> = _events

    private val _uploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

    /** 正在下载的附件：文件 id → 进度 */
    private val _downloads = MutableStateFlow<Map<UUID, Float>>(emptyMap())
    val downloads: StateFlow<Map<UUID, Float>> = _downloads.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /** 正在往上找原消息（可能要从服务端翻好几页） */
    private val _jumping = MutableStateFlow(false)
    val jumping: StateFlow<Boolean> = _jumping.asStateFlow()

    fun onDraftChange(text: String) {
        _draft.value = text.take(Limits.MESSAGE_BODY_MAX)
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val reply = _replyTo.value
        _draft.value = ""
        _replyTo.value = null
        viewModelScope.launch { chat.sendText(roomId, text, replyTo = reply) }
    }

    /** 选好了图片或文件：离线时不接受（上传必须在线），否则准备好后开始上传。 */
    fun attach(uri: Uri, asImage: Boolean) {
        if (!network.isOnline.value) {
            _events.tryEmit(ChatEvent.Toast(OFFLINE_ATTACH))
            return
        }
        viewModelScope.launch {
            val attachment = try {
                if (asImage) preparer.image(uri) else preparer.file(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentException) {
                _events.emit(ChatEvent.Toast(e.message ?: "发不了这个附件"))
                return@launch
            } catch (_: Exception) {
                _events.emit(ChatEvent.Toast(if (asImage) "这张图片打不开" else "读不到这个文件"))
                return@launch
            }
            val upload = Upload(UuidV7.generate(), attachment)
            _uploads.update { it + upload }
            runUpload(upload)
        }
    }

    fun retryUpload(id: UUID) {
        val upload = _uploads.value.firstOrNull { it.id == id } ?: return
        updateUpload(id) { it.copy(failed = null, progress = 0f) }
        viewModelScope.launch { runUpload(upload) }
    }

    fun cancelUpload(id: UUID) {
        val upload = _uploads.value.firstOrNull { it.id == id } ?: return
        _uploads.update { list -> list.filterNot { it.id == id } }
        preparer.cleanup(upload.attachment)
    }

    private suspend fun runUpload(upload: Upload) {
        val reply = _replyTo.value
        try {
            val file = chat.upload(roomId, upload.id, upload.attachment) { p -> updateUpload(upload.id) { it.copy(progress = p) } }
            _replyTo.value = if (_replyTo.value == reply) null else _replyTo.value
            chat.sendAttachment(roomId, file, replyTo = reply)
            _uploads.update { list -> list.filterNot { it.id == upload.id } }
            preparer.cleanup(upload.attachment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            updateUpload(upload.id) { it.copy(failed = e.userMessage) }
        } catch (_: Exception) {
            updateUpload(upload.id) { it.copy(failed = "网络断了，没发出去") }
        }
    }

    private fun updateUpload(id: UUID, change: (Upload) -> Upload) =
        _uploads.update { list -> list.map { if (it.id == id) change(it) else it } }

    /** 打开文件消息：先下载到本机缓存（下载过的直接用），再交给别的应用。 */
    fun openFile(file: FileMeta) {
        if (file.id in _downloads.value) return
        viewModelScope.launch {
            _downloads.update { it + (file.id to 0f) }
            try {
                val local = chat.download(file, File(context.cacheDir, "attachments")) { p -> _downloads.update { it + (file.id to p) } }
                _events.emit(ChatEvent.OpenFile(local, file.mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.emit(ChatEvent.Toast(if (network.isOnline.value) "下载失败，再试一次" else "离线时打不开还没下载过的文件"))
            } finally {
                _downloads.update { it - file.id }
            }
        }
    }

    fun offlineAttachHint() {
        _events.tryEmit(ChatEvent.Toast(OFFLINE_ATTACH))
    }

    /** 撤回自己的消息（不可恢复，界面先确认）。 */
    fun retract(message: Message) = viewModelScope.launch { chat.retract(message) }

    /** 删除进回收站。 */
    fun delete(message: Message) = viewModelScope.launch {
        chat.delete(message)
        _events.emit(ChatEvent.Toast("已移到回收站"))
    }

    fun openSearch() {
        _search.value = SearchState(open = true)
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    /** 输入停下 300 毫秒后再搜，避免每个字都请求一次。 */
    fun onSearchQuery(query: String) {
        _search.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            runSearch(reset = true)
        }
    }

    fun loadMoreResults() {
        val current = _search.value
        if (current.loading || current.nextCursor == null) return
        searchJob = viewModelScope.launch { runSearch(reset = false) }
    }

    /** 点搜索结果：关掉搜索，跳到那条消息。 */
    fun openResult(message: Message) {
        closeSearch()
        jumpTo(message.id)
    }

    private suspend fun runSearch(reset: Boolean) {
        val current = _search.value
        val query = MessageRules.searchQuery(current.query)
        if (query == null) {
            _search.update { it.copy(results = emptyList(), nextCursor = null, searched = false, loading = false) }
            return
        }
        if (!network.isOnline.value) {
            _search.update { it.copy(error = "离线时不能搜索", loading = false) }
            return
        }
        _search.update { it.copy(loading = true) }
        try {
            val page = chat.search(roomId, query, if (reset) null else current.nextCursor)
            _search.update {
                it.copy(
                    results = if (reset) page.messages else it.results + page.messages,
                    nextCursor = page.nextCursor, loading = false, searched = true, error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _search.update { it.copy(loading = false, error = "没搜成，稍后再试") }
        }
    }

    fun markRead(createdSeq: Long) = viewModelScope.launch { chat.markRead(roomId, createdSeq) }

    fun startReply(message: Message) {
        _replyTo.value = message
    }

    fun cancelReply() {
        _replyTo.value = null
    }

    /** 点回复里的摘要：跳回原文（需要时向上加载），并短暂高亮。 */
    fun jumpTo(messageId: UUID) {
        if (_jumping.value) return
        viewModelScope.launch {
            _jumping.value = true
            try {
                when (val position = chat.positionOf(roomId, messageId)) {
                    is ChatRepository.Position.Found -> _events.emit(ChatEvent.ScrollTo(position.index, messageId))
                    ChatRepository.Position.Deleted -> _events.emit(ChatEvent.Toast("原消息已删除"))
                    ChatRepository.Position.Missing -> _events.emit(ChatEvent.Toast("找不到原消息"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.emit(ChatEvent.Toast("没有联网，暂时找不到更早的消息"))
            } finally {
                _jumping.value = false
            }
        }
    }

    fun retry(message: Message) = viewModelScope.launch { chat.retry(message) }

    fun abandon(message: Message) = viewModelScope.launch { chat.abandon(message) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): ChatViewModel
    }

    private companion object {
        const val OFFLINE_ATTACH = "离线时不能发图片和文件"
    }
}
