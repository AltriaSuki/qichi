package app.qichi.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.EventRepository
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.QnaRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.zoneOf
import app.qichi.navigation.Page
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** 「一起」目录页：目录右侧的数字，底部的快速记灵感。 */
@HiltViewModel(assistedFactory = TogetherHubViewModel.Factory::class)
class TogetherHubViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val ideas: IdeaRepository,
    rooms: RoomRepository,
    moods: MoodRepository,
    qna: QnaRepository,
    plans: PlanRepository,
    todos: TodoRepository,
    events: EventRepository,
    session: SessionManager,
) : ViewModel() {
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 记下了一条：界面给一句轻提示 */
    val saved: SharedFlow<Unit> = _saved

    private val minuteTicker = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    private val lists = combine(
        moods.observeMoods(roomId), qna.observeRounds(roomId), plans.observePlans(roomId),
        todos.observeTodos(roomId), events.observeEvents(roomId),
    ) { m, r, p, t, e -> Lists(m.map { it.value }, r.map { it.value }, p.map { it.value }, t.map { it.value }, e.map { it.value }) }

    private data class Lists(
        val moods: List<app.qichi.shared.api.Mood>,
        val rounds: List<app.qichi.shared.api.QnaRound>,
        val plans: List<app.qichi.shared.api.Plan>,
        val todos: List<app.qichi.shared.api.Todo>,
        val events: List<app.qichi.shared.api.Event>,
    )

    val counts: StateFlow<Map<Page, String>> = combine(
        rooms.observeRoom(roomId), lists, ideas.observeIdeas(roomId), minuteTicker,
    ) { room, l, allIdeas, now ->
        hubCounts(HubData(session.currentUserId, zoneOf(room?.timezone), now, l.moods, l.rounds, l.plans, l.todos, l.events, allIdeas.map { it.value }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addIdea(text: String) = viewModelScope.launch {
        if (ideas.add(roomId, text) != null) _saved.tryEmit(Unit)
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TogetherHubViewModel
    }
}
