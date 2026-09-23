package app.qichi.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.DecisionRepository
import app.qichi.core.data.EventRepository
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.QnaRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.data.days
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.Local
import app.qichi.core.ui.currentStage
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Event
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.model.PlanStatus
import kotlinx.coroutines.CancellationException
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class TodayState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** 房间时区里的今天 */
    val today: LocalDate = LocalDate.now(),
    val partnerMood: Local<Mood>? = null,
    val myRepliesToPartner: List<MoodReply> = emptyList(),
    val myMood: Local<Mood>? = null,
    /** 截止在今天或更早、还没完成的顶层待办 */
    val todos: List<Local<Todo>> = emptyList(),
    /** 今天的日程 */
    val events: List<Local<Event>> = emptyList(),
    /** 今天的一问（轮次还没拉到时为空） */
    val round: QnaRound? = null,
    val question: Question? = null,
    /** 进行中的计划，最多三个 */
    val plans: List<TodayPlan> = emptyList(),
    /** 已经定下、复查日期到了的决定 */
    val reviews: List<Decision> = emptyList(),
    /** 去年今天的心情（本阶段「一年前」只显示心情） */
    val yearAgoMoods: List<Mood> = emptyList(),
) {
    val yearAgo: LocalDate get() = today.minusYears(1)
}

/** 今天页「进行中」里的一个计划。 */
data class TodayPlan(val plan: Plan, val stages: List<PlanStage>) {
    val currentStage: PlanStage? get() = currentStage(stages)
}

/** 今天页：当天的固定切片。按房间时区算「今天」，每分钟检查一次日期有没有变。 */
@HiltViewModel(assistedFactory = TodayViewModel.Factory::class)
class TodayViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    rooms: RoomRepository,
    private val moods: MoodRepository,
    private val todos: TodoRepository,
    events: EventRepository,
    private val qna: QnaRepository,
    plans: PlanRepository,
    decisions: DecisionRepository,
    session: SessionManager,
    val urls: FileUrls,
) : ViewModel() {

    private val minuteTicker = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }

    private data class More(
        val todos: List<Local<Todo>>,
        val events: List<Local<Event>>,
        val rounds: List<QnaRound>,
        val questions: List<Question>,
        val plans: List<Plan>,
        val stages: List<PlanStage>,
        val decisions: List<Decision> = emptyList(),
    )

    private val more = combine(
        combine(todos.observeTodos(roomId), events.observeEvents(roomId)) { t, e -> t to e },
        qna.observeRounds(roomId),
        qna.observeQuestions(roomId),
        plans.observePlans(roomId),
        combine(plans.observeStages(roomId), decisions.observeDecisions(roomId)) { st, d -> st to d },
    ) { (t, e), r, q, p, (st, d) -> More(t, e, r.map { it.value }, q.map { it.value }, p.map { it.value }, st.map { it.value }, d.map { it.value }) }

    val state: StateFlow<TodayState> = combine(
        people,
        moods.observeMoods(roomId),
        moods.observeReplies(roomId),
        more,
        minuteTicker,
    ) { p, allMoods, replies, more, now ->
        val allTodos = more.todos
        val allEvents = more.events
        val zone = zoneOf(p.room?.timezone)
        val today = todayIn(zone, now)
        val me = p.myUserId
        val partnerMood = allMoods.firstOrNull { it.value.authorId != me }
        fun dueOf(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
        TodayState(
            people = p,
            zone = zone,
            today = today,
            partnerMood = partnerMood,
            myRepliesToPartner = replies.map { it.value }.filter { it.moodId == partnerMood?.value?.id && it.authorId == me },
            myMood = allMoods.firstOrNull { it.value.authorId == me },
            todos = allTodos
                .filter { it.value.parentId == null && it.value.doneAt == null }
                .filter { t -> dueOf(t.value)?.let { !it.isAfter(today) } == true }
                .sortedWith(compareBy({ dueOf(it.value) }, { it.value.createdAt })),
            events = allEvents
                .filter { today in it.value.days(zone) }
                .sortedWith(compareBy<Local<Event>>({ !it.value.allDay }, { it.value.startsAt ?: Instant.MIN })),
            round = more.rounds.firstOrNull { it.roundDate == today },
            question = more.rounds.firstOrNull { it.roundDate == today }?.let { r -> more.questions.firstOrNull { it.id == r.questionId } },
            plans = more.plans.filter { it.status != PlanStatus.Done }
                .sortedWith(compareBy({ it.targetDate == null }, { it.targetDate }, { it.createdAt }))
                .take(3)
                .map { plan -> TodayPlan(plan, more.stages.filter { it.planId == plan.id }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))) },
            reviews = more.decisions.filter { d -> d.finalChoice != null && d.reviewDate?.let { !it.isAfter(today) } == true }
                .sortedBy { it.reviewDate },
            yearAgoMoods = allMoods.map { it.value }
                .filter { it.createdAt.atZone(zone).toLocalDate() == today.minusYears(1) }
                .sortedBy { it.createdAt },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    init {
        // 今天的轮次由服务端懒创建：拉一次写进本机；离线就用上次的
        viewModelScope.launch {
            try {
                qna.refreshToday(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun toggleTodo(todo: Todo, done: Boolean) = viewModelScope.launch {
        if (done) todos.complete(todo) else todos.reopen(todo)
    }

    fun toggleReply(kind: MoodReplyKind) {
        val s = state.value
        val mood = s.partnerMood?.value ?: return
        viewModelScope.launch {
            val existing = s.myRepliesToPartner.firstOrNull { it.kind == kind }
            if (existing != null) moods.withdraw(existing) else moods.respond(mood, kind)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TodayViewModel
    }
}
