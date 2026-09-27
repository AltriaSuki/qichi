package app.qichi.feature.plan

import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.Todo
import app.qichi.shared.model.PlanStatus
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 计划列表和详情里的归类、进度、下一步候选（P14-03）。 */
class PlanListsTest {
    private val room = UUID.randomUUID()
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")
    private val today = LocalDate.of(2026, 9, 27)
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun plan(title: String, status: PlanStatus = PlanStatus.Active, target: LocalDate? = null, created: Long = 0, updated: Long = 0, completed: Long? = null) = Plan(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0.plusSeconds(created), updatedAt = t0.plusSeconds(updated),
        deletedAt = null, deletedBy = null, title = title, ownerId = UUID.randomUUID(), status = status, targetDate = target,
        nextStep = null, nextStepOwnerId = null, nextStepDue = null, completedAt = completed?.let { t0.plusSeconds(it) }, completionNote = null,
    )

    private fun todo(planId: UUID?, title: String, done: Boolean = false, parent: UUID? = null, due: LocalDate? = null, created: Long = 0, deleted: Boolean = false) = Todo(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0.plusSeconds(created), updatedAt = t0,
        deletedAt = if (deleted) t0 else null, deletedBy = null, title = title, note = null, createdBy = UUID.randomUUID(),
        assigneeId = null, parentId = parent, dueDate = due, dueAt = null, recurrence = null, recurrencePrevId = null,
        doneAt = if (done) t0 else null, doneBy = null, planId = planId,
    )

    private fun stage(planId: UUID, done: Boolean, deleted: Boolean = false) = PlanStage(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0, updatedAt = t0, deletedAt = if (deleted) t0 else null, deletedBy = null,
        planId = planId, title = "阶段", sortOrder = 0, doneAt = if (done) t0 else null,
    )

    @Test
    fun `分三段：进行中按目标日（没有的在后），放一放最近动过的在前，已完成最近完成的在前`() {
        val soon = plan("月底搬家", target = LocalDate.of(2026, 9, 30))
        val later = plan("秋天去海边", target = LocalDate.of(2026, 11, 1))
        val open = plan("学做饭", created = 5)
        val openEarlier = plan("存钱", created = 1)
        val pausedOld = plan("学吉他", PlanStatus.Archived, updated = 1)
        val pausedNew = plan("练字", PlanStatus.Archived, updated = 9)
        val doneOld = plan("考驾照", PlanStatus.Done, completed = 1)
        val doneNew = plan("装修", PlanStatus.Done, completed = 9)
        val tabs = splitPlans(listOf(open, doneOld, later, pausedOld, soon, doneNew, openEarlier, pausedNew)) { it }
        assertEquals(listOf("月底搬家", "秋天去海边", "存钱", "学做饭"), tabs.active.map { it.title })
        assertEquals(listOf("练字", "学吉他"), tabs.paused.map { it.title })
        assertEquals(listOf("装修", "考驾照"), tabs.done.map { it.title })
    }

    @Test
    fun `过期：进行中且目标日已过；放一放和已完成的不算，今天到期的不算`() {
        assertTrue(isOverdue(plan("a", target = today.minusDays(1)), today))
        assertFalse(isOverdue(plan("b", target = today), today))
        assertFalse(isOverdue(plan("c"), today))
        assertFalse(isOverdue(plan("d", PlanStatus.Archived, target = today.minusDays(3)), today))
        assertFalse(isOverdue(plan("e", PlanStatus.Done, target = today.minusDays(3)), today))
    }

    @Test
    fun `进度：阶段和顶层待办各完成了几个；子任务和删掉的不算`() {
        val p = plan("搬家")
        val top = todo(p.id, "打包", done = true)
        val todos = listOf(top, todo(p.id, "找车"), todo(null, "子任务", done = true, parent = top.id), todo(p.id, "删掉的", deleted = true))
        val stages = listOf(stage(p.id, done = true), stage(p.id, done = false), stage(p.id, done = true, deleted = true))
        val progress = progressOf(stages, todos)
        assertEquals(PlanProgress(stagesDone = 1, stages = 2, todosDone = 1, todos = 2), progress)
        assertEquals(1, progress.openTodos)
    }

    @Test
    fun `下一步候选：这个计划里没做完的顶层待办，去掉正连着的；截止早的在前，没有截止的按先后`() {
        val p = plan("搬家")
        val linked = todo(p.id, "订车", due = LocalDate.of(2026, 9, 28))
        val late = todo(p.id, "退押金", due = LocalDate.of(2026, 10, 10))
        val early = todo(p.id, "打包", due = LocalDate.of(2026, 9, 29))
        val noDueFirst = todo(p.id, "告诉房东", created = 1)
        val noDueSecond = todo(p.id, "换地址", created = 2)
        val others = listOf(
            todo(p.id, "做完的", done = true), todo(p.id, "删掉的", deleted = true), todo(UUID.randomUUID(), "别的计划"),
            todo(null, "子任务", parent = linked.id),
        )
        val candidates = stepCandidates(p.id, listOf(noDueSecond, late, linked, noDueFirst, early) + others, zone, except = linked.id)
        assertEquals(listOf("打包", "退押金", "告诉房东", "换地址"), candidates.map { it.title })
    }
}
