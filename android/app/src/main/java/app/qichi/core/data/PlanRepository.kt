package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CreateMilestoneRequest
import app.qichi.shared.api.CreatePlanLogRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateMilestoneRequest
import app.qichi.shared.api.UpdatePlanLogRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdatePlanStageRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 计划、阶段、里程碑、过程记录的本机读写：先写本机（界面立即变化）再经发件箱发出，离线也能改。
 * 读到的列表都不含回收站里的。
 */
class PlanRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    fun observePlans(roomId: UUID): Flow<List<Local<Plan>>> = observe(roomId, EntityType.Plan)
    fun observeStages(roomId: UUID): Flow<List<Local<PlanStage>>> = observe(roomId, EntityType.PlanStage)
    fun observeMilestones(roomId: UUID): Flow<List<Local<Milestone>>> = observe(roomId, EntityType.Milestone)
    fun observeLogs(roomId: UUID): Flow<List<Local<PlanLog>>> = observe(roomId, EntityType.PlanLog)

    private fun <T : app.qichi.shared.api.SyncEntity> observe(roomId: UUID, type: EntityType): Flow<List<Local<T>>> =
        db.entities().observeByType(roomId.toString(), type.wireName).map { rows -> rows.map { LocalStore.toLocal<T>(it) } }

    // ── 计划 ──

    suspend fun create(roomId: UUID, title: String, ownerId: UUID, targetDate: LocalDate?): Plan {
        val now = clock.instant()
        val plan = Plan(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            title = title.trim(), ownerId = ownerId, status = PlanStatus.Active, targetDate = targetDate,
            nextStep = null, nextStepOwnerId = null, nextStepDue = null, completedAt = null, completionNote = null,
        )
        store.writeLocal(roomId, plan, OutboxOp.post("rooms/$roomId/plans", CreatePlanRequest(plan.id, plan.title, ownerId, targetDate)))
        scheduler.kickOutbox()
        return plan
    }

    /**
     * 修改：本机按改动字段更新，PATCH 只含改动的字段。
     * 直接改下一步的文字、谁来做或截止时，下一步不再跟着待办（和服务端一样，P14-03）。
     */
    suspend fun update(plan: Plan, change: UpdatePlanRequest) {
        var p = plan.copy(updatedAt = clock.instant())
        (change.title as? Patch.Value)?.let { p = p.copy(title = it.value.trim()) }
        (change.ownerId as? Patch.Value)?.let { p = p.copy(ownerId = it.value) }
        (change.status as? Patch.Value)?.let { p = p.copy(status = it.value) }
        (change.targetDate as? Patch.Value)?.let { p = p.copy(targetDate = it.value) }
        (change.nextStep as? Patch.Value)?.let { p = p.copy(nextStep = it.value?.trim()) }
        (change.nextStepOwnerId as? Patch.Value)?.let { p = p.copy(nextStepOwnerId = it.value) }
        (change.nextStepDue as? Patch.Value)?.let { p = p.copy(nextStepDue = it.value) }
        (change.coverFileId as? Patch.Value)?.let { p = p.copy(coverFileId = it.value) }
        (change.nextStepTodoId as? Patch.Value)?.let { p = p.copy(nextStepTodoId = it.value) }
        if (!change.nextStepTodoId.isPresent && listOf(change.nextStep, change.nextStepOwnerId, change.nextStepDue).any { it.isPresent }) {
            p = p.copy(nextStepTodoId = null)
        }
        store.writeLocal(plan.roomId, p, OutboxOp.patch("rooms/${plan.roomId}/plans/${plan.id}", change))
        scheduler.kickOutbox()
    }

    /** 先放一放、接着做、重新打开（P14-03）。重新打开时完成时间清掉，完成记录留着。完成要用 [complete]。 */
    suspend fun setStatus(plan: Plan, status: PlanStatus) {
        if (status == plan.status || status == PlanStatus.Done) return
        val p = plan.copy(status = status, completedAt = if (plan.status == PlanStatus.Done) null else plan.completedAt, updatedAt = clock.instant())
        store.writeLocal(plan.roomId, p, OutboxOp.patch("rooms/${plan.roomId}/plans/${plan.id}", UpdatePlanRequest(status = Patch.of(status))))
        scheduler.kickOutbox()
    }

    /**
     * 下一步用计划里的一件待办（P14-03）：本机照那件待办填（只有时刻的截止按房间时区 [zone] 算日期），
     * 服务端也照它填，之后跟着它变；它做完或删掉时下一步结束。
     */
    suspend fun linkNextStep(plan: Plan, todo: Todo, zone: ZoneId) {
        val p = plan.copy(
            nextStep = todo.title, nextStepOwnerId = todo.assigneeId,
            nextStepDue = todo.dueDate ?: todo.dueAt?.atZone(zone)?.toLocalDate(),
            nextStepTodoId = todo.id, updatedAt = clock.instant(),
        )
        store.writeLocal(plan.roomId, p, OutboxOp.patch("rooms/${plan.roomId}/plans/${plan.id}", UpdatePlanRequest(nextStepTodoId = Patch.of(todo.id))))
        scheduler.kickOutbox()
    }

    /** 完成计划：写下完成记录。 */
    suspend fun complete(plan: Plan, note: String) {
        val now = clock.instant()
        val text = note.trim()
        store.writeLocal(
            plan.roomId,
            plan.copy(status = PlanStatus.Done, completedAt = now, completionNote = text, updatedAt = now),
            OutboxOp.post("rooms/${plan.roomId}/plans/${plan.id}/complete", CompletePlanRequest(text)),
        )
        scheduler.kickOutbox()
    }

    suspend fun delete(plan: Plan) {
        val now = clock.instant()
        store.writeLocal(plan.roomId, plan.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${plan.roomId}/plans/${plan.id}"))
        scheduler.kickOutbox()
    }

    // ── 阶段 ──

    suspend fun addStage(plan: Plan, title: String, sortOrder: Int) {
        val now = clock.instant()
        val stage = PlanStage(
            id = UuidV7.generate(), roomId = plan.roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            planId = plan.id, title = title.trim(), sortOrder = sortOrder, doneAt = null,
        )
        store.writeLocal(plan.roomId, stage, OutboxOp.post("rooms/${plan.roomId}/plans/${plan.id}/stages", CreatePlanStageRequest(stage.id, stage.title, sortOrder)))
        scheduler.kickOutbox()
    }

    suspend fun setStageDone(stage: PlanStage, done: Boolean) {
        val now = clock.instant()
        val at = if (done) now else null
        store.writeLocal(
            stage.roomId, stage.copy(doneAt = at, updatedAt = now),
            OutboxOp.patch("rooms/${stage.roomId}/plans/${stage.planId}/stages/${stage.id}", UpdatePlanStageRequest(doneAt = Patch.of(at))),
        )
        scheduler.kickOutbox()
    }

    suspend fun renameStage(stage: PlanStage, title: String) {
        store.writeLocal(
            stage.roomId, stage.copy(title = title.trim(), updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${stage.roomId}/plans/${stage.planId}/stages/${stage.id}", UpdatePlanStageRequest(title = Patch.of(title.trim()))),
        )
        scheduler.kickOutbox()
    }

    /** 阶段换顺序（P14-03）：按 [ordered] 的先后重新编号 0、1、2……，只发编号变了的。 */
    suspend fun reorderStages(ordered: List<PlanStage>) {
        val now = clock.instant()
        db.transaction {
            ordered.forEachIndexed { i, stage ->
                if (stage.sortOrder != i) {
                    store.writeLocal(
                        stage.roomId, stage.copy(sortOrder = i, updatedAt = now),
                        OutboxOp.patch("rooms/${stage.roomId}/plans/${stage.planId}/stages/${stage.id}", UpdatePlanStageRequest(sortOrder = Patch.of(i))),
                    )
                }
            }
        }
        scheduler.kickOutbox()
    }

    suspend fun deleteStage(stage: PlanStage) {
        val now = clock.instant()
        store.writeLocal(stage.roomId, stage.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${stage.roomId}/plans/${stage.planId}/stages/${stage.id}"))
        scheduler.kickOutbox()
    }

    // ── 里程碑 ──

    suspend fun addMilestone(plan: Plan, title: String, targetDate: LocalDate?) {
        val now = clock.instant()
        val m = Milestone(
            id = UuidV7.generate(), roomId = plan.roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            planId = plan.id, title = title.trim(), targetDate = targetDate, doneAt = null,
        )
        store.writeLocal(plan.roomId, m, OutboxOp.post("rooms/${plan.roomId}/plans/${plan.id}/milestones", CreateMilestoneRequest(m.id, m.title, targetDate)))
        scheduler.kickOutbox()
    }

    suspend fun setMilestoneDone(m: Milestone, done: Boolean) {
        val now = clock.instant()
        val at = if (done) now else null
        store.writeLocal(
            m.roomId, m.copy(doneAt = at, updatedAt = now),
            OutboxOp.patch("rooms/${m.roomId}/plans/${m.planId}/milestones/${m.id}", UpdateMilestoneRequest(doneAt = Patch.of(at))),
        )
        scheduler.kickOutbox()
    }

    /** 里程碑改名、改日期（P14-03）；只发改动的字段。 */
    suspend fun updateMilestone(m: Milestone, title: String, targetDate: LocalDate?) {
        val t = title.trim()
        val change = UpdateMilestoneRequest(
            title = if (t != m.title) Patch.of(t) else Patch.Absent,
            targetDate = if (targetDate != m.targetDate) Patch.of(targetDate) else Patch.Absent,
        )
        if (change == UpdateMilestoneRequest()) return
        store.writeLocal(
            m.roomId, m.copy(title = t, targetDate = targetDate, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${m.roomId}/plans/${m.planId}/milestones/${m.id}", change),
        )
        scheduler.kickOutbox()
    }

    suspend fun deleteMilestone(m: Milestone) {
        val now = clock.instant()
        store.writeLocal(m.roomId, m.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${m.roomId}/plans/${m.planId}/milestones/${m.id}"))
        scheduler.kickOutbox()
    }

    // ── 进展记录（记的人可以改、可以删，P14-03）──

    suspend fun addLog(plan: Plan, body: String) {
        val now = clock.instant()
        val log = PlanLog(
            id = UuidV7.generate(), roomId = plan.roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            planId = plan.id, authorId = me, body = body.trim(),
        )
        store.writeLocal(plan.roomId, log, OutboxOp.post("rooms/${plan.roomId}/plans/${plan.id}/logs", CreatePlanLogRequest(log.id, log.body)))
        scheduler.kickOutbox()
    }

    /** 改自己记的一条进展。 */
    suspend fun updateLog(log: PlanLog, body: String) {
        val text = body.trim()
        if (text == log.body) return
        store.writeLocal(
            log.roomId, log.copy(body = text, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${log.roomId}/plans/${log.planId}/logs/${log.id}", UpdatePlanLogRequest(text)),
        )
        scheduler.kickOutbox()
    }

    /** 删自己记的一条进展（进回收站）。 */
    suspend fun deleteLog(log: PlanLog) {
        val now = clock.instant()
        store.writeLocal(log.roomId, log.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${log.roomId}/plans/${log.planId}/logs/${log.id}"))
        scheduler.kickOutbox()
    }
}
