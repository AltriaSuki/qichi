package app.qichi.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.EventDraft
import app.qichi.core.data.EventRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.days
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Event
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 某一天的日程：全天的在前，定时的按开始时间。 */
data class EventDay(val date: LocalDate, val events: List<Local<Event>>)

/**
 * 按房间时区把日程分到每一天，只保留从 [today] 起的 [daysAhead] 天。
 * 「今天」由调用方按房间时区算好传入（不是手机时区）。
 */
fun groupEventsByDay(events: List<Local<Event>>, zone: ZoneId, today: LocalDate, daysAhead: Long = 60): List<EventDay> {
    val last = today.plusDays(daysAhead)
    val byDay = sortedMapOf<LocalDate, MutableList<Local<Event>>>()
    for (e in events) {
        for (d in e.value.days(zone)) {
            if (!d.isBefore(today) && !d.isAfter(last)) byDay.getOrPut(d) { mutableListOf() } += e
        }
    }
    return byDay.map { (date, list) ->
        EventDay(date, list.sortedWith(compareBy<Local<Event>>({ !it.value.allDay }, { it.value.startsAt ?: Instant.MIN })))
    }
}

data class EventListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val days: List<EventDay> = emptyList(),
    val all: List<Local<Event>> = emptyList(),
)

@HiltViewModel(assistedFactory = EventListViewModel.Factory::class)
class EventListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val events: EventRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    val state: StateFlow<EventListState> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), events.observeEvents(roomId)) { room, members, all ->
        val zone = zoneOf(room?.timezone)
        val today = todayIn(zone)
        EventListState(People(room, members, session.currentUserId), zone, today, groupEventsByDay(all, zone, today), all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventListState())

    fun save(existing: Event?, draft: EventDraft) = viewModelScope.launch {
        if (existing == null) events.create(roomId, draft) else events.update(existing, draft)
    }

    fun delete(event: Event) = viewModelScope.launch { events.delete(event) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): EventListViewModel
    }
}
