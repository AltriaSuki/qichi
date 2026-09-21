package app.qichi.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.ChatRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.Local
import app.qichi.shared.api.Message
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatState(
    val people: People = People.Empty,
    val online: Boolean = true,
)

/** 一次性的界面事件。 */
sealed interface ChatEvent {
    /** 滚到第 [index] 条并短暂高亮 [id] */
    data class ScrollTo(val index: Int, val id: UUID) : ChatEvent
    data class Toast(val text: String) : ChatEvent
}

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val chat: ChatRepository,
    rooms: RoomRepository,
    network: NetworkMonitor,
    session: SessionManager,
) : ViewModel() {

    val messages: Flow<PagingData<Local<Message>>> = chat.messages(roomId).cachedIn(viewModelScope)

    val state: StateFlow<ChatState> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), network.isOnline) { room, members, online ->
        ChatState(People(room, members, session.currentUserId), online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    /** 最新一条消息的 id 与作者：界面据此决定跟到底部还是提示「新消息」 */
    val newest: StateFlow<Message?> = chat.observeNewest(roomId).map { it?.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** 正在回复的消息（输入框上方显示） */
    private val _replyTo = MutableStateFlow<Message?>(null)
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatEvent> = _events

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
}
