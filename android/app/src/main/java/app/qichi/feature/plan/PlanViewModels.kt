package app.qichi.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.sync.Local
import app.qichi.core.ui.currentStage
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.rules.Limits
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

/** 计划列表里的一行：计划、当前阶段、未完成的待办数。 */
data class PlanSummary(
    val plan: Local<Plan>,
    val currentStage: PlanStage?,
    val openTodos: Int,
)

data class PlanListState(
    val people: People = People.Empty,
    val today: LocalDate = LocalDate.now(),
    val active: List<PlanSummary> = emptyList(),
    val done: List<PlanSummary> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = PlanListViewModel.Factory::class)
class PlanListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val plans: PlanRepository,
    rooms: RoomRepository,
    todos: TodoRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }

    val state: StateFlow<PlanListState> = combine(
        people, plans.observePlans(roomId), plans.observeStages(roomId), todos.observeTodos(roomId),
    ) { p, all, stages, allTodos ->
        val byPlan = stages.map { it.value }.groupBy { it.planId }
        val openByPlan = allTodos.map { it.value }.filter { it.doneAt == null && it.parentId == null }.groupingBy { it.planId }.eachCount()
        val summaries = all.map { PlanSummary(it, currentStage(byPlan[it.value.id].orEmpty()), openByPlan[it.value.id] ?: 0) }
        PlanListState(
            people = p,
            today = todayIn(zoneOf(p.room?.timezone)),
            // 进行中的：有目标日的按目标日在前，其余按创建时间
            active = summaries.filter { it.plan.value.status != PlanStatus.Done }
                .sortedWith(compareBy({ it.plan.value.targetDate == null }, { it.plan.value.targetDate }, { it.plan.value.createdAt })),
            done = summaries.filter { it.plan.value.status == PlanStatus.Done }.sortedByDescending { it.plan.value.completedAt },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanListState())

    fun create(title: String, ownerId: UUID, targetDate: LocalDate?, onCreated: (UUID) -> Unit) = viewModelScope.launch {
        val t = title.trim()
        if (t.length !in Limits.PLAN_TITLE_LENGTH) return@launch
        onCreated(plans.create(roomId, t, ownerId, targetDate).id)
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): PlanListViewModel
    }
}

/** 计划里的一条顶层待办和它的子任务。 */
data class PlanTodo(val todo: Local<Todo>, val children: List<Local<Todo>>)

data class PlanDetailState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    /** 还没读出来，或已经删除时为空 */
    val plan: Local<Plan>? = null,
    val stages: List<PlanStage> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val openTodos: List<PlanTodo> = emptyList(),
    val doneTodos: List<PlanTodo> = emptyList(),
    /** 最新的在前 */
    val logs: List<PlanLog> = emptyList(),
    val loaded: Boolean = false,
) {
    val currentStage: PlanStage? get() = currentStage(stages)
}

@HiltViewModel(assistedFactory = PlanDetailViewModel.Factory::class)
class PlanDetailViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("planId") private val planId: UUID,
    private val plans: PlanRepository,
    private val todos: TodoRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }
    private val parts = combine(
        plans.observeStages(roomId), plans.observeMilestones(roomId), plans.observeLogs(roomId), todos.observeTodos(roomId),
    ) { stages, milestones, logs, allTodos -> Parts(stages, milestones, logs, allTodos) }

    private data class Parts(
        val stages: List<Local<PlanStage>>,
        val milestones: List<Local<Milestone>>,
        val logs: List<Local<PlanLog>>,
        val todos: List<Local<Todo>>,
    )

    val state: StateFlow<PlanDetailState> = combine(people, plans.observePlans(roomId), parts) { p, all, parts ->
        val zone = zoneOf(p.room?.timezone)
        val children = parts.todos.filter { it.value.parentId != null }.groupBy { it.value.parentId }
        val mine = parts.todos.filter { it.value.planId == planId && it.value.parentId == null }
            .map { PlanTodo(it, children[it.value.id].orEmpty().sortedBy { c -> c.value.createdAt }) }
        fun due(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
        PlanDetailState(
            people = p,
            zone = zone,
            today = todayIn(zone),
            plan = all.firstOrNull { it.value.id == planId },
            stages = parts.stages.map { it.value }.filter { it.planId == planId }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt })),
            milestones = parts.milestones.map { it.value }.filter { it.planId == planId }
                .sortedWith(compareBy({ it.targetDate == null }, { it.targetDate }, { it.createdAt })),
            openTodos = mine.filter { it.todo.value.doneAt == null }
                .sortedWith(compareBy({ due(it.todo.value) == null }, { due(it.todo.value) }, { it.todo.value.createdAt })),
            doneTodos = mine.filter { it.todo.value.doneAt != null }.sortedByDescending { it.todo.value.doneAt }.take(10),
            logs = parts.logs.map { it.value }.filter { it.planId == planId }.sortedByDescending { it.createdAt },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanDetailState())

    private fun plan(): Plan? = state.value.plan?.value

    // ── 计划本身 ──

    /** 编辑标题、负责人、目标日；只发改动过的字段。 */
    fun edit(title: String, ownerId: UUID, targetDate: LocalDate?) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val t = title.trim()
        if (t.length !in Limits.PLAN_TITLE_LENGTH) return@launch
        val change = UpdatePlanRequest(
            title = if (t != plan.title) Patch.of(t) else Patch.Absent,
            ownerId = if (ownerId != plan.ownerId) Patch.of(ownerId) else Patch.Absent,
            targetDate = if (targetDate != plan.targetDate) Patch.of(targetDate) else Patch.Absent,
        )
        if (change != UpdatePlanRequest()) plans.update(plan, change)
    }

    /** 写下或修改下一步；清空文字就是没有下一步。 */
    fun setNextStep(text: String, ownerId: UUID?, due: LocalDate?) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val step = text.trim().take(Limits.PLAN_STEP_LENGTH.last).ifEmpty { null }
        val owner = if (step == null) null else ownerId
        val date = if (step == null) null else due
        val change = UpdatePlanRequest(
            nextStep = if (step != plan.nextStep) Patch.of(step) else Patch.Absent,
            nextStepOwnerId = if (owner != plan.nextStepOwnerId) Patch.of(owner) else Patch.Absent,
            nextStepDue = if (date != plan.nextStepDue) Patch.of(date) else Patch.Absent,
        )
        if (change != UpdatePlanRequest()) plans.update(plan, change)
    }

    /** 下一步做完了：记进过程记录，再清空，等写下新的下一步。 */
    fun completeNextStep() = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val step = plan.nextStep ?: return@launch
        plans.addLog(plan, "完成了：$step")
        plans.update(plan, UpdatePlanRequest(nextStep = Patch.of(null), nextStepOwnerId = Patch.of(null), nextStepDue = Patch.of(null)))
    }

    fun complete(note: String) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val text = note.trim()
        if (text.length in Limits.PLAN_LOG_LENGTH) plans.complete(plan, text)
    }

    fun delete() = viewModelScope.launch { plan()?.let { plans.delete(it) } }

    // ── 阶段、里程碑 ──

    fun toggleStage(stage: PlanStage) = viewModelScope.launch { plans.setStageDone(stage, stage.doneAt == null) }

    fun addStage(title: String) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val t = title.trim()
        if (t.length in Limits.PLAN_TITLE_LENGTH) plans.addStage(plan, t, (state.value.stages.maxOfOrNull { it.sortOrder } ?: -1) + 1)
    }

    fun renameStage(stage: PlanStage, title: String) = viewModelScope.launch {
        val t = title.trim()
        if (t.length in Limits.PLAN_TITLE_LENGTH && t != stage.title) plans.renameStage(stage, t)
    }

    fun deleteStage(stage: PlanStage) = viewModelScope.launch { plans.deleteStage(stage) }

    fun toggleMilestone(m: Milestone) = viewModelScope.launch { plans.setMilestoneDone(m, m.doneAt == null) }

    fun addMilestone(title: String, date: LocalDate?) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val t = title.trim()
        if (t.length in Limits.PLAN_TITLE_LENGTH) plans.addMilestone(plan, t, date)
    }

    fun deleteMilestone(m: Milestone) = viewModelScope.launch { plans.deleteMilestone(m) }

    // ── 待办、记录 ──

    fun toggleTodo(todo: Todo, done: Boolean) = viewModelScope.launch { if (done) todos.complete(todo) else todos.reopen(todo) }

    fun addTodo(title: String) = viewModelScope.launch {
        val t = title.trim()
        if (t.isNotEmpty()) todos.create(roomId = roomId, title = t, planId = planId)
    }

    fun addLog(body: String) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val text = body.trim()
        if (text.length in Limits.PLAN_LOG_LENGTH) plans.addLog(plan, text)
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("planId") planId: UUID): PlanDetailViewModel
    }
}
