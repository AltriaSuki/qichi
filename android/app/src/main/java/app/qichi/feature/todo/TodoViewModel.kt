package app.qichi.feature.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.rules.Recurrence
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

enum class Assignee { Both, Me, Partner }
enum class Repeat(val label: String) { None("不重复"), Daily("每天"), Weekly("每周"), Monthly("每月") }

/** 编辑表单。重复需要截止日：选了重复却没选日期时，截止日取今天。 */
data class TodoForm(
    val title: String = "",
    val note: String = "",
    val assignee: Assignee = Assignee.Both,
    val dueDate: LocalDate? = null,
    val repeat: Repeat = Repeat.None,
    /** 属于哪个计划；空 = 不属于 */
    val planId: UUID? = null,
) {
    val canSave: Boolean get() = title.trim().isNotEmpty()

    fun recurrence(): String? {
        val due = dueDate ?: return null
        return when (repeat) {
            Repeat.None -> null
            Repeat.Daily -> Recurrence(Recurrence.Freq.DAILY).format()
            Repeat.Weekly -> Recurrence(Recurrence.Freq.WEEKLY, byDay = setOf(due.dayOfWeek)).format()
            Repeat.Monthly -> Recurrence(Recurrence.Freq.MONTHLY).format()
        }
    }

    companion object {
        fun of(todo: Todo, people: People, zone: ZoneId): TodoForm = TodoForm(
            title = todo.title,
            note = todo.note.orEmpty(),
            assignee = when (todo.assigneeId) {
                null -> Assignee.Both
                people.myUserId -> Assignee.Me
                else -> Assignee.Partner
            },
            dueDate = todo.dueDate ?: todo.dueAt?.atZone(zone)?.toLocalDate(),
            repeat = when (todo.recurrence?.let(Recurrence::parse)?.freq) {
                Recurrence.Freq.DAILY -> Repeat.Daily
                Recurrence.Freq.WEEKLY -> Repeat.Weekly
                Recurrence.Freq.MONTHLY -> Repeat.Monthly
                null -> Repeat.None
            },
            planId = todo.planId,
        )
    }
}

/** 一条顶层待办和它的子任务。 */
data class TodoGroup(val todo: Local<Todo>, val children: List<Local<Todo>>)

data class TodoUiState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val open: List<TodoGroup> = emptyList(),
    val done: List<TodoGroup> = emptyList(),
    /** 可以挂靠的计划（进行中的） */
    val plans: List<Plan> = emptyList(),
) {
    fun find(id: UUID): TodoGroup? = (open + done).firstOrNull { it.todo.value.id == id }
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
            plans = allPlans.map { it.value }.filter { it.status != PlanStatus.Done }.sortedBy { it.createdAt },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState())

    fun toggle(todo: Todo, done: Boolean) = viewModelScope.launch {
        if (done) todos.complete(todo) else todos.reopen(todo)
    }

    fun create(form: TodoForm) = viewModelScope.launch {
        val s = state.value
        val due = form.dueDate ?: if (form.repeat != Repeat.None) s.today else null
        val fixed = form.copy(dueDate = due)
        todos.create(
            roomId = roomId,
            title = fixed.title,
            assigneeId = assigneeId(fixed.assignee, s.people),
            dueDate = due,
            recurrence = fixed.recurrence(),
            note = fixed.note,
            planId = fixed.planId,
        )
    }

    /** 保存修改：只发改动过的字段。 */
    fun update(todo: Todo, form: TodoForm) = viewModelScope.launch {
        val s = state.value
        val due = form.dueDate ?: if (form.repeat != Repeat.None) s.today else null
        val fixed = form.copy(dueDate = due)
        val before = TodoForm.of(todo, s.people, s.zone)
        val change = UpdateTodoRequest(
            title = if (fixed.title.trim() != todo.title) Patch.of(fixed.title.trim()) else Patch.Absent,
            note = if (fixed.note.trim() != todo.note.orEmpty()) Patch.of(fixed.note.trim().ifEmpty { null }) else Patch.Absent,
            assigneeId = if (fixed.assignee != before.assignee) Patch.of(assigneeId(fixed.assignee, s.people)) else Patch.Absent,
            dueDate = if (due != before.dueDate || todo.dueAt != null) Patch.of(due) else Patch.Absent,
            dueAt = if (todo.dueAt != null && due != before.dueDate) Patch.of(null) else Patch.Absent,
            recurrence = if (fixed.recurrence() != todo.recurrence) Patch.of(fixed.recurrence()) else Patch.Absent,
            planId = if (fixed.planId != todo.planId) Patch.of(fixed.planId) else Patch.Absent,
        )
        if (change != UpdateTodoRequest()) todos.update(todo, change)
    }

    fun delete(group: TodoGroup) = viewModelScope.launch {
        todos.delete(group.todo.value, group.children.map { it.value })
    }

    fun addSubtask(parent: Todo, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) todos.create(roomId = roomId, title = title, parentId = parent.id)
    }

    private fun assigneeId(a: Assignee, people: People): UUID? = when (a) {
        Assignee.Both -> null
        Assignee.Me -> people.myUserId
        Assignee.Partner -> people.partner?.userId
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TodoViewModel
    }
}
