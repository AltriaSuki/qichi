package app.qichi.feature.plan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.AttachmentException
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.data.FileRepository
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.network.FileUrls
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.Local
import app.qichi.core.ui.TodoForm
import app.qichi.core.ui.TodoGroup
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
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 计划列表里的一行：计划、当前阶段、阶段和待办完成了几成（P14-03）。 */
data class PlanSummary(
    val plan: Local<Plan>,
    val currentStage: PlanStage?,
    val progress: PlanProgress = PlanProgress(),
    /** 阶段（排好序），列表卡片上画阶段线 */
    val stages: List<PlanStage> = emptyList(),
    /** 最近一个还没完成的里程碑的日期 */
    val nextMilestone: LocalDate? = null,
)

/** 计划列表：进行中、放一放、已完成三段（P14-03，排法见 [splitPlans]）。 */
data class PlanListState(
    val people: People = People.Empty,
    val today: LocalDate = LocalDate.now(),
    val active: List<PlanSummary> = emptyList(),
    val paused: List<PlanSummary> = emptyList(),
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
    val urls: FileUrls,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }

    val state: StateFlow<PlanListState> = combine(
        people, plans.observePlans(roomId), plans.observeStages(roomId), todos.observeTodos(roomId), plans.observeMilestones(roomId),
    ) { p, all, stages, allTodos, milestones ->
        val byPlan = stages.map { it.value }.groupBy { it.planId }.mapValues { (_, v) -> v.sortedWith(compareBy({ it.sortOrder }, { it.createdAt })) }
        val todosByPlan = allTodos.map { it.value }.filter { it.planId != null }.groupBy { it.planId }
        val nextMilestone = milestones.map { it.value }.filter { it.doneAt == null && it.targetDate != null }
            .groupBy { it.planId }.mapValues { (_, v) -> v.minOf { it.targetDate!! } }
        val summaries = all.map {
            val st = byPlan[it.value.id].orEmpty()
            PlanSummary(it, currentStage(st), progressOf(st, todosByPlan[it.value.id].orEmpty()), st, nextMilestone[it.value.id])
        }
        val tabs = splitPlans(summaries) { it.plan.value }
        PlanListState(
            people = p,
            today = todayIn(zoneOf(p.room?.timezone)),
            active = tabs.active,
            paused = tabs.paused,
            done = tabs.done,
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

data class PlanDetailState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    /** 还没读出来，或已经删除时为空 */
    val plan: Local<Plan>? = null,
    val stages: List<PlanStage> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val openTodos: List<TodoGroup> = emptyList(),
    val doneTodos: List<TodoGroup> = emptyList(),
    /** 最新的在前 */
    val logs: List<PlanLog> = emptyList(),
    /** 阶段、待办完成了几成（P14-03） */
    val progress: PlanProgress = PlanProgress(),
    /** 下一步连着的待办（P14-03）；没连着、或那件待办已经不在了时为空 */
    val linkedTodo: Todo? = null,
    /** 编辑计划里的待办时能挂的计划：进行中的，加上这一个 */
    val plans: List<Plan> = emptyList(),
    val loaded: Boolean = false,
) {
    val currentStage: PlanStage? get() = currentStage(stages)

    /** 下一步可以用的待办：没做完的顶层待办，正连着的除外（P14-03） */
    val stepCandidates: List<Todo>
        get() = openTodos.map { it.todo.value }.filter { it.id != plan?.value?.nextStepTodoId }

    fun findTodo(id: UUID): TodoGroup? = (openTodos + doneTodos).firstOrNull { it.todo.value.id == id }
}

@HiltViewModel(assistedFactory = PlanDetailViewModel.Factory::class)
class PlanDetailViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("planId") private val planId: UUID,
    private val plans: PlanRepository,
    private val todos: TodoRepository,
    rooms: RoomRepository,
    session: SessionManager,
    private val preparer: AttachmentPreparer,
    private val files: FileRepository,
    private val network: NetworkMonitor,
    val urls: FileUrls,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }

    /** 正在上传封面：进度 0–1；没在传时为空 */
    private val _coverUpload = MutableStateFlow<Float?>(null)
    val coverUpload: StateFlow<Float?> = _coverUpload.asStateFlow()
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** 给人看的一句提示（Toast） */
    val message: SharedFlow<String> = _message

    /** 换封面：先上传照片（要联网），再改计划（走发件箱）。 */
    fun setCover(uri: Uri) {
        val plan = plan() ?: return
        if (!network.isOnline.value) {
            _message.tryEmit("离线时换不了封面")
            return
        }
        viewModelScope.launch {
            _coverUpload.value = 0f
            try {
                val attachment = preparer.image(uri)
                val file = try {
                    files.upload(roomId, UuidV7.generate(), attachment) { p -> _coverUpload.value = p }
                } finally {
                    preparer.cleanup(attachment)
                }
                plans.update(plan, UpdatePlanRequest(coverFileId = Patch.of(file.id)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentException) {
                _message.emit(e.message ?: "这张照片用不了")
            } catch (_: Exception) {
                _message.emit("没传上去，再试一次")
            } finally {
                _coverUpload.value = null
            }
        }
    }

    /** 不要照片了，改回插画。 */
    fun clearCover() = viewModelScope.launch {
        val plan = plan() ?: return@launch
        if (plan.coverFileId != null) plans.update(plan, UpdatePlanRequest(coverFileId = Patch.of(null)))
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
            .map { TodoGroup(it, children[it.value.id].orEmpty().sortedBy { c -> c.value.createdAt }) }
        fun due(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
        val plan = all.firstOrNull { it.value.id == planId }
        val stages = parts.stages.map { it.value }.filter { it.planId == planId }.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        PlanDetailState(
            people = p,
            zone = zone,
            today = todayIn(zone),
            plan = plan,
            stages = stages,
            milestones = parts.milestones.map { it.value }.filter { it.planId == planId }
                .sortedWith(compareBy({ it.targetDate == null }, { it.targetDate }, { it.createdAt })),
            openTodos = mine.filter { it.todo.value.doneAt == null }
                .sortedWith(compareBy({ due(it.todo.value) == null }, { due(it.todo.value) }, { it.todo.value.createdAt })),
            doneTodos = mine.filter { it.todo.value.doneAt != null }.sortedByDescending { it.todo.value.doneAt }.take(10),
            logs = parts.logs.map { it.value }.filter { it.planId == planId }.sortedByDescending { it.createdAt },
            progress = progressOf(stages, mine.map { it.todo.value }),
            linkedTodo = plan?.value?.nextStepTodoId?.let { id -> parts.todos.firstOrNull { it.value.id == id }?.value }
                ?.takeIf { it.deletedAt == null && it.doneAt == null },
            plans = all.map { it.value }.filter { it.status == PlanStatus.Active || it.id == planId }.sortedBy { it.createdAt },
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

    /**
     * 写下或修改下一步；清空文字就是没有下一步。连着待办时在这里改，就不再跟着那件待办（P14-03）。
     * [asTodo]：同时加进计划的待办，下一步就连着它（做完那件待办，下一步跟着结束）。
     */
    fun setNextStep(text: String, ownerId: UUID?, due: LocalDate?, asTodo: Boolean = false) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val step = text.trim().take(Limits.PLAN_STEP_LENGTH.last).ifEmpty { null }
        if (asTodo && step != null) {
            val todo = todos.create(roomId = roomId, title = step.take(Limits.TODO_TITLE_LENGTH.last), assigneeId = ownerId, dueDate = due, planId = planId)
            plans.linkNextStep(plan, todo, state.value.zone)
            return@launch
        }
        val owner = if (step == null) null else ownerId
        val date = if (step == null) null else due
        // 什么都没改就什么都不发（连着待办时也照样连着）
        val change = UpdatePlanRequest(
            nextStep = if (step != plan.nextStep) Patch.of(step) else Patch.Absent,
            nextStepOwnerId = if (owner != plan.nextStepOwnerId) Patch.of(owner) else Patch.Absent,
            nextStepDue = if (date != plan.nextStepDue) Patch.of(date) else Patch.Absent,
        )
        if (change != UpdatePlanRequest()) plans.update(plan, change)
    }

    /** 下一步用计划里的这件待办（P14-03）。 */
    fun linkNextStep(todo: Todo) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        if (plan.nextStepTodoId != todo.id) plans.linkNextStep(plan, todo, state.value.zone)
    }

    /**
     * 下一步做完了。连着待办时就是勾掉那件待办（下一步跟着结束，P14-03）；
     * 没连着时记进进展记录，再清空，等写下或挑一个新的下一步。
     */
    fun completeNextStep() = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val step = plan.nextStep ?: return@launch
        val linked = state.value.linkedTodo
        if (linked != null) {
            todos.complete(linked)
        } else {
            plans.addLog(plan, "完成了：$step")
            plans.update(plan, UpdatePlanRequest(
                nextStep = Patch.of(null), nextStepOwnerId = Patch.of(null), nextStepDue = Patch.of(null),
                nextStepTodoId = if (plan.nextStepTodoId != null) Patch.of(null) else Patch.Absent,
            ))
        }
    }

    /** 先放一放、接着做、重新打开（P14-03）。 */
    fun setStatus(status: PlanStatus) = viewModelScope.launch { plan()?.let { plans.setStatus(it, status) } }

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

    /** 阶段往前（[by] = -1）或往后（1）挪一格（P14-03）。 */
    fun moveStage(stage: PlanStage, by: Int) = viewModelScope.launch {
        val list = state.value.stages
        val from = list.indexOfFirst { it.id == stage.id }
        val to = from + by
        if (from < 0 || to !in list.indices) return@launch
        plans.reorderStages(list.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun toggleMilestone(m: Milestone) = viewModelScope.launch { plans.setMilestoneDone(m, m.doneAt == null) }

    fun addMilestone(title: String, date: LocalDate?) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val t = title.trim()
        if (t.length in Limits.PLAN_TITLE_LENGTH) plans.addMilestone(plan, t, date)
    }

    fun deleteMilestone(m: Milestone) = viewModelScope.launch { plans.deleteMilestone(m) }

    /** 里程碑改名、改日期（P14-03）。 */
    fun editMilestone(m: Milestone, title: String, date: LocalDate?) = viewModelScope.launch {
        val t = title.trim()
        if (t.length in Limits.PLAN_TITLE_LENGTH) plans.updateMilestone(m, t, date)
    }

    // ── 待办、记录 ──

    fun toggleTodo(todo: Todo, done: Boolean) = viewModelScope.launch { if (done) todos.complete(todo) else todos.reopen(todo) }

    fun addTodo(title: String) = viewModelScope.launch {
        val t = title.trim()
        if (t.isNotEmpty()) todos.create(roomId = roomId, title = t, planId = planId)
    }

    /** 改计划里的一件待办（P14-03，和待办页同一个编辑面板）：只发改动过的字段。 */
    fun editTodo(todo: Todo, form: TodoForm) = viewModelScope.launch {
        val s = state.value
        val change = form.fixed(s.today).changesFrom(todo, s.people, s.zone)
        if (change != UpdateTodoRequest()) todos.update(todo, change)
    }

    fun deleteTodo(group: TodoGroup) = viewModelScope.launch { todos.delete(group.todo.value, group.children.map { it.value }) }

    fun addSubtask(parent: Todo, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) todos.create(roomId = roomId, title = title, parentId = parent.id)
    }

    fun addLog(body: String) = viewModelScope.launch {
        val plan = plan() ?: return@launch
        val text = body.trim()
        if (text.length in Limits.PLAN_LOG_LENGTH) plans.addLog(plan, text)
    }

    /** 改自己记的一条进展（P14-03）；别人的改不了（服务端也会拒绝）。 */
    fun editLog(log: PlanLog, body: String) = viewModelScope.launch {
        val text = body.trim()
        if (log.authorId == state.value.people.myUserId && text.length in Limits.PLAN_LOG_LENGTH) plans.updateLog(log, text)
    }

    fun deleteLog(log: PlanLog) = viewModelScope.launch {
        if (log.authorId == state.value.people.myUserId) plans.deleteLog(log)
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("planId") planId: UUID): PlanDetailViewModel
    }
}
