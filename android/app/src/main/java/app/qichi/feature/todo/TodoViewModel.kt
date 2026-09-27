package app.qichi.feature.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.TodoForm
import app.qichi.core.ui.TodoGroup
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Plan
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.PlanStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 待办页的三段：今天（含逾期）、这周（到周日）、以后（含没有截止日的）。 */
data class TodoSections(val today: List<TodoGroup>, val week: List<TodoGroup>, val later: List<TodoGroup>)

fun todoSections(open: List<TodoGroup>, today: LocalDate, zone: ZoneId): TodoSections {
    val sunday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
    fun due(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
    val (todayList, rest) = open.partition { g -> due(g.todo.value)?.let { !it.isAfter(today) } == true }
    val (week, later) = rest.partition { g -> due(g.todo.value)?.let { !it.isAfter(sunday) } == true }
    return TodoSections(todayList, week, later)
}

data class TodoUiState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val open: List<TodoGroup> = emptyList(),
    val done: List<TodoGroup> = emptyList(),
    /** 可以挂靠的计划（进行中的） */
    val plans: List<Plan> = emptyList(),
    /** 所有计划（待办下面那行小字「⚑ 秋天去一次海边」；编辑时补上已经挂着的那个） */
    val allPlans: Map<UUID, Plan> = emptyMap(),
    /** 已经从本机读出来了（桌面组件点进来要打开某一条时，读出来之前先等着，P15-02） */
    val loaded: Boolean = false,
) {
    val sections: TodoSections get() = todoSections(open, today, zone)
    val planTitles: Map<UUID, String> get() = allPlans.mapValues { it.value.title }
    fun find(id: UUID): TodoGroup? = (open + done).firstOrNull { it.todo.value.id == id }

    /** 编辑 [todo] 时能选的计划：进行中的，加上它现在挂着的（先放一放或已完成的也列出来，免得看不出挂在哪）。 */
    fun plansFor(todo: Todo?): List<Plan> {
        val current = todo?.planId?.let(allPlans::get)
        return if (current == null || plans.any { it.id == current.id }) plans else plans + current
    }
}

@HiltViewModel(assistedFactory = TodoViewModel.Factory::class)
class TodoViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val todos: TodoRepository,
    rooms: RoomRepository,
    plans: PlanRepository,
    session: SessionManager,
) : ViewModel() {

    val state: StateFlow<TodoUiState> = combine(
        rooms.observeRoom(roomId),
        rooms.observeMembers(roomId),
        todos.observeTodos(roomId),
        plans.observePlans(roomId),
    ) { room, members, all, allPlans ->
        val people = People(room, members, session.currentUserId)
        val zone = zoneOf(room?.timezone)
        val today = todayIn(zone)
        val children = all.filter { it.value.parentId != null }.groupBy { it.value.parentId }
        val groups = all.filter { it.value.parentId == null }.map { top ->
            TodoGroup(top, children[top.value.id].orEmpty().sortedBy { it.value.createdAt })
        }
        fun due(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
        TodoUiState(
            people = people,
            zone = zone,
            today = today,
            // 有截止的按截止日在前，没截止的按创建时间
            open = groups.filter { it.todo.value.doneAt == null }
                .sortedWith(compareBy<TodoGroup>({ due(it.todo.value) == null }, { due(it.todo.value) }, { it.todo.value.createdAt })),
            done = groups.filter { it.todo.value.doneAt != null }
                .sortedByDescending { it.todo.value.doneAt }
                .take(30),
            plans = allPlans.map { it.value }.filter { it.status == PlanStatus.Active }.sortedBy { it.createdAt },
            allPlans = allPlans.associate { it.value.id to it.value },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState())

    fun toggle(todo: Todo, done: Boolean) = viewModelScope.launch {
        if (done) todos.complete(todo) else todos.reopen(todo)
    }

    fun create(form: TodoForm) = viewModelScope.launch {
        val s = state.value
        val fixed = form.fixed(s.today)
        todos.create(
            roomId = roomId,
            title = fixed.title,
            assigneeId = fixed.assigneeId(s.people),
            dueDate = fixed.dueDate,
            recurrence = fixed.recurrence(),
            note = fixed.note,
            planId = fixed.planId,
        )
    }

    /** 保存修改：只发改动过的字段。 */
    fun update(todo: Todo, form: TodoForm) = viewModelScope.launch {
        val s = state.value
        val change = form.fixed(s.today).changesFrom(todo, s.people, s.zone)
        if (change != UpdateTodoRequest()) todos.update(todo, change)
    }

    fun delete(group: TodoGroup) = viewModelScope.launch {
        todos.delete(group.todo.value, group.children.map { it.value })
    }

    fun addSubtask(parent: Todo, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) todos.create(roomId = roomId, title = title, parentId = parent.id)
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TodoViewModel
    }
}
