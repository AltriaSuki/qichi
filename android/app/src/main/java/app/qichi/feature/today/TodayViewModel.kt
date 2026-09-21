package app.qichi.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.EventRepository
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.data.days
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Event
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MoodReplyKind
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
    /** 去年今天的心情（本阶段「一年前」只显示心情） */
    val yearAgoMoods: List<Mood> = emptyList(),
) {
    val yearAgo: LocalDate get() = today.minusYears(1)
}

/** 今天页：当天的固定切片。按房间时区算「今天」，每分钟检查一次日期有没有变。 */
@HiltViewModel(assistedFactory = TodayViewModel.Factory::class)
class TodayViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    rooms: RoomRepository,
    private val moods: MoodRepository,
    private val todos: TodoRepository,
    events: EventRepository,
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

    val state: StateFlow<TodayState> = combine(
        people,
        moods.observeMoods(roomId),
        moods.observeReplies(roomId),
        combine(todos.observeTodos(roomId), events.observeEvents(roomId)) { t, e -> t to e },
        minuteTicker,
    ) { p, allMoods, replies, (allTodos, allEvents), now ->
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
            yearAgoMoods = allMoods.map { it.value }
                .filter { it.createdAt.atZone(zone).toLocalDate() == today.minusYears(1) }
                .sortedBy { it.createdAt },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

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
