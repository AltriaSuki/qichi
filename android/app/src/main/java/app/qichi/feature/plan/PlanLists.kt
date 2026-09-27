package app.qichi.feature.plan

import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.Todo
import app.qichi.shared.model.PlanStatus
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 一个计划完成了几成（P14-03）：阶段、待办（顶层的，子任务不算）。 */
data class PlanProgress(val stagesDone: Int = 0, val stages: Int = 0, val todosDone: Int = 0, val todos: Int = 0) {
    val openTodos: Int get() = todos - todosDone
}

fun progressOf(planStages: List<PlanStage>, planTodos: List<Todo>): PlanProgress {
    val top = planTodos.filter { it.parentId == null && it.deletedAt == null }
    val stages = planStages.filter { it.deletedAt == null }
    return PlanProgress(stages.count { it.doneAt != null }, stages.size, top.count { it.doneAt != null }, top.size)
}

/** 计划列表的三段（P14-03）。 */
data class PlanTabs<T>(val active: List<T>, val paused: List<T>, val done: List<T>)

/** 进行中的按目标日排（没有目标日的在后，再按创建先后）；放一放的最近动过的在前；已完成的最近完成的在前。 */
fun <T> splitPlans(items: List<T>, plan: (T) -> Plan): PlanTabs<T> {
    val (done, rest) = items.partition { plan(it).status == PlanStatus.Done }
    val (paused, active) = rest.partition { plan(it).status == PlanStatus.Archived }
    return PlanTabs(
        active = active.sortedWith(compareBy({ plan(it).targetDate == null }, { plan(it).targetDate }, { plan(it).createdAt })),
        paused = paused.sortedByDescending { plan(it).updatedAt },
        done = done.sortedByDescending { plan(it).completedAt },
    )
}

/** 进行中、目标日已经过了。 */
fun isOverdue(plan: Plan, today: LocalDate): Boolean =
    plan.status == PlanStatus.Active && plan.targetDate?.isBefore(today) == true

/**
 * 下一步可以用的待办（P14-03）：计划里没做完、没删的顶层待办，[except]（刚做完的、正连着的）除外；
 * 有截止的在前（早的先），再按创建先后。
 */
fun stepCandidates(planId: UUID, todos: List<Todo>, zone: ZoneId, except: UUID? = null): List<Todo> {
    fun due(t: Todo): LocalDate? = t.dueDate ?: t.dueAt?.atZone(zone)?.toLocalDate()
    return todos.filter { it.planId == planId && it.parentId == null && it.doneAt == null && it.deletedAt == null && it.id != except }
        .sortedWith(compareBy({ due(it) == null }, { due(it) }, { it.createdAt }))
}
