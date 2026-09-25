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
import app.qichi.shared.api.UpdateMilestoneRequest
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

    /** 修改：本机按改动字段更新，PATCH 只含改动的字段。 */
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
        store.writeLocal(plan.roomId, p, OutboxOp.patch("rooms/${plan.roomId}/plans/${plan.id}", change))
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

    suspend fun deleteMilestone(m: Milestone) {
        val now = clock.instant()
        store.writeLocal(m.roomId, m.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${m.roomId}/plans/${m.planId}/milestones/${m.id}"))
        scheduler.kickOutbox()
    }

    // ── 过程记录（写下后不可修改）──

    suspend fun addLog(plan: Plan, body: String) {
        val now = clock.instant()
        val log = PlanLog(
            id = UuidV7.generate(), roomId = plan.roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            planId = plan.id, authorId = me, body = body.trim(),
        )
        store.writeLocal(plan.roomId, log, OutboxOp.post("rooms/${plan.roomId}/plans/${plan.id}/logs", CreatePlanLogRequest(log.id, log.body)))
        scheduler.kickOutbox()
    }
}
