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

    /** 发出一条之后通知界面滚到底 */
    private val _sent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sent: SharedFlow<Unit> = _sent

    fun onDraftChange(text: String) {
        _draft.value = text.take(Limits.MESSAGE_BODY_MAX)
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            chat.sendText(roomId, text)
            _sent.tryEmit(Unit)
        }
    }

    fun retry(message: Message) = viewModelScope.launch { chat.retry(message) }

    fun abandon(message: Message) = viewModelScope.launch { chat.abandon(message) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): ChatViewModel
    }
}
