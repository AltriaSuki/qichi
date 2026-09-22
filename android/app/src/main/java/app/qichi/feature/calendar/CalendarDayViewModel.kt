package app.qichi.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.EventRepository
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CalendarDayState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val date: LocalDate = LocalDate.now(),
    val items: CalendarDayItems = CalendarDayItems(date),
)

@HiltViewModel(assistedFactory = CalendarDayViewModel.Factory::class)
class CalendarDayViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    @Assisted date: LocalDate,
    rooms: RoomRepository,
    events: EventRepository,
    todos: TodoRepository,
    plans: PlanRepository,
    session: SessionManager,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(date)
    private val minuteTicker = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }
    private val sources = combine(
        events.observeEvents(roomId), todos.observeTodos(roomId),
        plans.observePlans(roomId), plans.observeMilestones(roomId),
    ) { allEvents, allTodos, allPlans, allMilestones ->
        CalendarSources(allEvents, allTodos, allPlans, allMilestones)
    }
    val state: StateFlow<CalendarDayState> = combine(people, sources, selectedDate, minuteTicker) { people, source, date, now ->
        val zone = zoneOf(people.room?.timezone)
        CalendarDayState(people, zone, todayIn(zone, now), date,
            calendarItems(date, date, zone, source.events, source.todos, source.plans, source.milestones)[date] ?: CalendarDayItems(date))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarDayState(date = date))

    fun move(days: Long) { selectedDate.value = selectedDate.value.plusDays(days) }
    fun today() { selectedDate.value = state.value.today }

    @AssistedFactory interface Factory {
        fun create(roomId: UUID, date: LocalDate): CalendarDayViewModel
    }
}
