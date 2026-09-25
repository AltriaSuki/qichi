package app.qichi.feature.qna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.QnaRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.Local
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Answer
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class QnaTab { Today, Library }

data class QnaUiState(
    val people: People = People.Empty,
    val todayRound: Local<QnaRound>? = null,
    val todayQuestion: Local<Question>? = null,
    val myAnswer: Local<Answer>? = null,
    val partnerAnswer: Local<Answer>? = null,
    val yesterdayRound: Local<QnaRound>? = null,
    val yesterdayQuestion: Local<Question>? = null,
    val yesterdayAnswers: List<Local<Answer>> = emptyList(),
    val adopted: List<Local<Question>> = emptyList(),
    val suggested: List<Local<Question>> = emptyList(),
    val online: Boolean = false,
    val tab: QnaTab = QnaTab.Today,
    val draft: String = "",
    val editing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** 今天是第几题（到今天为止一共出过几轮） */
    val roundNumber: Int = 0,
) {
    val confirmed: Boolean get() = people.myUserId in (todayRound?.value?.confirmedBy ?: emptyList())
    val partnerConfirmed: Boolean get() = people.partner?.userId in (todayRound?.value?.confirmedBy ?: emptyList())
    val revealed: Boolean get() = todayRound?.value?.revealedAt != null
}

private data class QnaData(
    val people: People,
    val rounds: List<Local<QnaRound>>,
    val questions: List<Local<Question>>,
    val answers: List<Local<Answer>>,
)

private data class Editor(
    val tab: QnaTab = QnaTab.Today,
    val draft: String = "",
    val editing: Boolean = false,
    val busy: Boolean = false,
    val suggestionJobId: UUID? = null,
    val error: String? = null,
)

@HiltViewModel(assistedFactory = QnaViewModel.Factory::class)
class QnaViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val qna: QnaRepository,
    rooms: RoomRepository,
    session: SessionManager,
    private val network: NetworkMonitor,
    private val realtime: RealtimeClient,
) : ViewModel() {
    private val editor = MutableStateFlow(Editor())
    private val data = combine(
        rooms.observeRoom(roomId), rooms.observeMembers(roomId),
        qna.observeRounds(roomId), qna.observeQuestions(roomId), qna.observeAnswers(roomId),
    ) { room, members, rounds, questions, answers ->
        QnaData(People(room, members, session.currentUserId), rounds, questions, answers)
    }

    val state: StateFlow<QnaUiState> = combine(data, network.isOnline, editor) { data, online, e ->
        val today = todayIn(zoneOf(data.people.room?.timezone))
        val round = data.rounds.firstOrNull { it.value.roundDate == today }
        val previous = data.rounds.firstOrNull { it.value.roundDate == today.minusDays(1) }
        val byId = data.questions.associateBy { it.value.id }
        val mine = data.answers.firstOrNull { it.value.roundId == round?.value?.id && it.value.authorId == data.people.myUserId }
        val partner = data.answers.firstOrNull { it.value.roundId == round?.value?.id && it.value.authorId != data.people.myUserId }
            ?.takeIf { round?.value?.revealedAt != null }
        QnaUiState(
            people = data.people,
            todayRound = round,
            todayQuestion = round?.value?.questionId?.let(byId::get),
            myAnswer = mine,
            partnerAnswer = partner,
            yesterdayRound = previous,
            yesterdayQuestion = previous?.value?.questionId?.let(byId::get),
            yesterdayAnswers = data.answers.filter {
                it.value.roundId == previous?.value?.id &&
                    (it.value.authorId == data.people.myUserId || previous?.value?.revealedAt != null)
            },
            adopted = data.questions.filter { it.value.adoptedAt != null && it.value.deletedAt == null }
                .sortedByDescending { it.value.createdAt },
            suggested = data.questions.filter { it.value.adoptedAt == null && it.value.deletedAt == null }
                .sortedByDescending { it.value.createdAt },
            online = online,
            tab = e.tab,
            draft = if (e.editing) e.draft else mine?.value?.body.orEmpty(),
            editing = e.editing,
            busy = e.busy,
            error = e.error,
            roundNumber = data.rounds.count { !it.value.roundDate.isAfter(today) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QnaUiState())

    init {
        refresh()
        viewModelScope.launch {
            realtime.aiDone.collect { event ->
                if (event.roomId == roomId) editor.update { current ->
                    if (event.jobId == current.suggestionJobId) current.copy(
                        busy = false,
                        suggestionJobId = null,
                        error = if (event.status == AiJobStatus.Failed.wireName) "没能生成问题" else null,
                    ) else current
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        try {
            qna.refreshToday(roomId)
            editor.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 离线时保留 Room 里的上次内容。
        }
    }

    fun tab(tab: QnaTab) = editor.update { it.copy(tab = tab, error = null) }
    fun edit() = editor.update { it.copy(editing = true, draft = state.value.myAnswer?.value?.body.orEmpty()) }
    fun draft(value: String) = editor.update { it.copy(editing = true, draft = value.take(Limits.ANSWER_BODY_LENGTH.last)) }

    fun save() = viewModelScope.launch {
        val s = state.value
        val round = s.todayRound?.value ?: return@launch
        if (s.confirmed || s.draft.trim().isEmpty()) return@launch
        try {
            qna.writeAnswer(round, s.myAnswer?.value, s.draft)
            editor.update { it.copy(editing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(error = "没能保存回答") }
        }
    }

    fun confirm() = viewModelScope.launch {
        val s = state.value
        val round = s.todayRound?.value ?: return@launch
        if (s.confirmed || s.draft.trim().isEmpty()) return@launch
        try {
            if (s.myAnswer?.value?.body != s.draft.trim()) qna.writeAnswer(round, s.myAnswer?.value, s.draft)
            qna.confirm(round)
            editor.update { it.copy(editing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(error = "没能确认回答") }
        }
    }

    fun addQuestion(text: String) = viewModelScope.launch {
        try {
            qna.createQuestion(roomId, text)
            editor.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(error = "没能加入题库") }
        }
    }

    fun adopt(question: Question) = viewModelScope.launch {
        try {
            qna.adopt(question)
            editor.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(error = "没能采纳问题") }
        }
    }

    fun delete(question: Question) = viewModelScope.launch {
        try {
            qna.delete(question)
            editor.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(error = "没能移除问题") }
        }
    }

    fun suggest() = viewModelScope.launch {
        if (!state.value.online || state.value.busy) return@launch
        editor.update { it.copy(busy = true, error = null) }
        try {
            val job = qna.suggest(roomId)
            editor.update { it.copy(suggestionJobId = job.jobId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            editor.update { it.copy(busy = false, suggestionJobId = null, error = "没能生成问题") }
        }
    }

    @AssistedFactory interface Factory {
        fun create(roomId: UUID): QnaViewModel
    }
}
