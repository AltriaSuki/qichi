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

enum class CalendarView { Week, Month }

data class CalendarMonthState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val view: CalendarView = CalendarView.Month,
    val dayItems: Map<LocalDate, CalendarDayItems> = emptyMap(),
) {
    val monthStart: LocalDate get() = selectedDate.withDayOfMonth(1)
    val selectedItems: CalendarDayItems get() = dayItems[selectedDate] ?: CalendarDayItems(selectedDate)
}

@HiltViewModel(assistedFactory = CalendarMonthViewModel.Factory::class)
class CalendarMonthViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    rooms: RoomRepository,
    events: EventRepository,
    todos: TodoRepository,
    plans: PlanRepository,
    session: SessionManager,
) : ViewModel() {
    private val selection = MutableStateFlow<LocalDate?>(null)
    private val view = MutableStateFlow(CalendarView.Month)
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

    val state: StateFlow<CalendarMonthState> = combine(people, sources, selection, view, minuteTicker) { people, source, chosen, mode, now ->
        val zone = zoneOf(people.room?.timezone)
        val today = todayIn(zone, now)
        val date = chosen ?: today
        val first = date.withDayOfMonth(1).minusDays(7)
        val last = date.withDayOfMonth(date.lengthOfMonth()).plusDays(7)
        CalendarMonthState(
            people = people,
            zone = zone,
            today = today,
            selectedDate = date,
            view = mode,
            dayItems = calendarItems(first, last, zone, source.events, source.todos, source.plans, source.milestones),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarMonthState())

    fun select(date: LocalDate) { selection.value = date }
    fun show(mode: CalendarView) { view.value = mode }
    fun move(direction: Long) {
        val date = state.value.selectedDate
        selection.value = when (view.value) {
            CalendarView.Month -> date.plusMonths(direction)
            CalendarView.Week -> date.plusWeeks(direction)
        }
    }
    fun today() { selection.value = state.value.today }

    @AssistedFactory interface Factory {
        fun create(roomId: UUID): CalendarMonthViewModel
    }
}
