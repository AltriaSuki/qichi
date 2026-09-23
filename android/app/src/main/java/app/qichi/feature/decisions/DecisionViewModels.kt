package app.qichi.feature.decisions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.DecisionRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateDecisionRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 复查日期到了（今天或更早）。 */
fun Decision.reviewDue(today: LocalDate): Boolean = reviewDate?.let { !it.isAfter(today) } == true

data class DecisionListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    /** 还没定的，最新的在前 */
    val open: List<Local<Decision>> = emptyList(),
    /** 定了的：该复查的在前，其余按定下的时间 */
    val decided: List<Local<Decision>> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = DecisionListViewModel.Factory::class)
class DecisionListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val decisions: DecisionRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    val state: StateFlow<DecisionListState> = combine(people, decisions.observeDecisions(roomId)) { p, all ->
        val zone = zoneOf(p.room?.timezone)
        val today = todayIn(zone)
        DecisionListState(
            people = p, zone = zone, today = today,
            open = all.filter { it.value.finalChoice == null }.sortedByDescending { it.value.createdAt },
            decided = all.filter { it.value.finalChoice != null }
                .sortedWith(compareByDescending<Local<Decision>> { it.value.reviewDue(today) }.thenByDescending { it.value.decidedAt }),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DecisionListState())

    fun create(question: String, options: List<String>, reviewDate: LocalDate?, onCreated: (UUID) -> Unit) = viewModelScope.launch {
        decisions.create(roomId, question, options, reviewDate)?.let { onCreated(it.id) }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): DecisionListViewModel
    }
}

data class DecisionDetailState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val decision: Local<Decision>? = null,
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = DecisionDetailViewModel.Factory::class)
class DecisionDetailViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("decisionId") private val decisionId: UUID,
    private val decisions: DecisionRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    val state: StateFlow<DecisionDetailState> = combine(people, decisions.observeDecisions(roomId)) { p, all ->
        val zone = zoneOf(p.room?.timezone)
        DecisionDetailState(p, zone, todayIn(zone), all.firstOrNull { it.value.id == decisionId }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DecisionDetailState())

    private fun update(change: UpdateDecisionRequest) = viewModelScope.launch {
        state.value.decision?.value?.let { decisions.update(it, change) }
    }

    fun setQuestion(text: String) {
        val q = text.trim()
        if (q.isNotEmpty() && q != state.value.decision?.value?.question) update(UpdateDecisionRequest(question = Patch.of(q)))
    }

    fun addOption(text: String) {
        val d = state.value.decision?.value ?: return
        val next = decisions.cleanOptions(d.options + text)
        if (next != d.options) update(UpdateDecisionRequest(options = Patch.of(next)))
    }

    fun removeOption(option: String) {
        val d = state.value.decision?.value ?: return
        update(UpdateDecisionRequest(options = Patch.of(d.options - option)))
    }

    fun setMyConcern(text: String) = update(UpdateDecisionRequest(myConcern = Patch.of(text.trim().ifEmpty { null })))

    fun decide(choice: String) {
        if (choice.isNotBlank()) update(UpdateDecisionRequest(finalChoice = Patch.of(choice.trim())))
    }

    fun reopen() = update(UpdateDecisionRequest(finalChoice = Patch.of(null)))

    fun setReviewDate(date: LocalDate?) {
        if (date != state.value.decision?.value?.reviewDate) update(UpdateDecisionRequest(reviewDate = Patch.of(date)))
    }

    fun delete() = viewModelScope.launch { state.value.decision?.value?.let { decisions.delete(it) } }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("decisionId") decisionId: UUID): DecisionDetailViewModel
    }
}
