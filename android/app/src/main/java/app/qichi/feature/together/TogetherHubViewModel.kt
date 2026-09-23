package app.qichi.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.IdeaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 「一起」目录页：底部的快速记灵感。 */
@HiltViewModel(assistedFactory = TogetherHubViewModel.Factory::class)
class TogetherHubViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val ideas: IdeaRepository,
) : ViewModel() {
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 记下了一条：界面给一句轻提示 */
    val saved: SharedFlow<Unit> = _saved

    fun addIdea(text: String) = viewModelScope.launch {
        if (ideas.add(roomId, text) != null) _saved.tryEmit(Unit)
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TogetherHubViewModel
    }
}
