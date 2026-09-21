package app.qichi.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.ChatRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** 底部「聊天」标签上的未读数。 */
@HiltViewModel(assistedFactory = UnreadViewModel.Factory::class)
class UnreadViewModel @AssistedInject constructor(
    @Assisted roomId: UUID,
    chat: ChatRepository,
) : ViewModel() {
    val count: StateFlow<Int> = chat.observeUnread(roomId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): UnreadViewModel
    }
}
