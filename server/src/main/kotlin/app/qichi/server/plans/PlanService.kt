package app.qichi.server.plans

import org.jetbrains.exposed.v1.jdbc.select
import app.qichi.shared.model.FileKind
import app.qichi.server.db.Files
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Milestones
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CreateMilestoneRequest
import app.qichi.shared.api.CreatePlanLogRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanDetail
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.UpdateMilestoneRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdatePlanStageRequest
import app.qichi.shared.api.ifPresent
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

class PlanService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun activeMember(roomId: UUID, userId: UUID) =
        RoomRepository.activeMembers(roomId).any { it.userId == userId }

    private fun checkTitle(title: String): String {
        val value = title.trim()
        validate { check(value.length in Limits.PLAN_TITLE_LENGTH, "title", "标题 1–200 字") }
        return value
    }

    private fun checkStep(step: String?): String? {
        val value = step?.trim()
        validate { check(value == null || value.length in Limits.PLAN_STEP_LENGTH, "nextStep", "下一步 1–1000 字") }
        return value
    }

    suspend fun list(userId: UUID, roomId: UUID): List<Plan> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }
            .orderBy(Plans.createdAt, SortOrder.DESC).map { it.toPlan() }
    }

    suspend fun detail(userId: UUID, roomId: UUID, id: UUID): PlanDetail = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        val plan = Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }
            .singleOrNull()?.toPlan() ?: notFound()
        PlanDetail(plan,
            PlanStages.selectAll().where { (PlanStages.planId eq id) and PlanStages.deletedAt.isNull() }
                .orderBy(PlanStages.sortOrder).map { it.toPlanStage() },
            Milestones.selectAll().where { (Milestones.planId eq id) and Milestones.deletedAt.isNull() }
                .orderBy(Milestones.createdAt).map { it.toMilestone() },
            PlanLogs.selectAll().where { PlanLogs.planId eq id }
                .orderBy(PlanLogs.createdAt, SortOrder.DESC).map { it.toPlanLog() },
        )
    }

    suspend fun create(userId: UUID, roomId: UUID, request: CreatePlanRequest): Pair<Plan, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        val title = checkTitle(request.title)
        val step = checkStep(request.nextStep)
        val stepOwner = request.nextStepOwnerId
        validate {
            check(activeMember(roomId, request.ownerId), "ownerId", "负责人必须是房间成员")
            check(stepOwner == null || activeMember(roomId, stepOwner), "nextStepOwnerId", "负责人必须是房间成员")
            check(step != null || (stepOwner == null && request.nextStepDue == null), "nextStep", "先填写下一步")
        }
        writes.create(this, roomId, userId, EntityType.Plan, request.id, Plans,
            { id -> Plans.selectAll().where { Plans.id eq id }.singleOrNull()?.toPlan() }) {
            it[Plans.title] = title
            it[Plans.ownerId] = request.ownerId
            it[Plans.status] = PlanStatus.Active.wireName
            it[Plans.targetDate] = request.targetDate
            it[Plans.nextStep] = step
            it[Plans.nextStepOwnerId] = stepOwner
            it[Plans.nextStepDue] = request.nextStepDue
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, request: UpdatePlanRequest): Plan = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }
            .singleOrNull() ?: notFound()
        validate {
            check(listOf(request.title, request.ownerId, request.status, request.targetDate, request.nextStep,
                request.nextStepOwnerId, request.nextStepDue, request.coverFileId).any { it.isPresent }, "body", "至少修改一个字段")
            request.coverFileId.ifPresent { fileId ->
                if (fileId != null) {
                    val kind = Files.select(Files.kind).where { (Files.id eq fileId) and (Files.roomId eq roomId) }.singleOrNull()?.get(Files.kind)
                    check(kind == FileKind.Image.wireName || kind == FileKind.Hero.wireName, "coverFileId", "封面要是这个房间里的一张图片")
                }
            }
            request.ownerId.ifPresent { check(activeMember(roomId, it), "ownerId", "负责人必须是房间成员") }
            request.nextStepOwnerId.ifPresent { owner ->
                check(owner == null || activeMember(roomId, owner), "nextStepOwnerId", "负责人必须是房间成员")
            }
            request.status.ifPresent { check(it != PlanStatus.Done, "status", "请用完成计划操作") }
        }
        val title = (request.title as? Patch.Value)?.value?.let(::checkTitle)
        val step = if (request.nextStep.isPresent) checkStep(request.nextStep.orNull()) else row[Plans.nextStep]
        val stepOwner = if (request.nextStepOwnerId.isPresent) request.nextStepOwnerId.orNull() else row[Plans.nextStepOwnerId]
        val stepDue = if (request.nextStepDue.isPresent) request.nextStepDue.orNull() else row[Plans.nextStepDue]
        validate { check(step != null || (stepOwner == null && stepDue == null), "nextStep", "先填写下一步") }
        writes.update(this, roomId, userId, EntityType.Plan, id, Plans) {
            if (title != null) it[Plans.title] = title
            request.ownerId.ifPresent { value -> it[Plans.ownerId] = value }
            request.status.ifPresent { value -> it[Plans.status] = value.wireName }
            request.targetDate.ifPresent { value -> it[Plans.targetDate] = value }
            if (request.nextStep.isPresent) it[Plans.nextStep] = step
            request.nextStepOwnerId.ifPresent { value -> it[Plans.nextStepOwnerId] = value }
            request.nextStepDue.ifPresent { value -> it[Plans.nextStepDue] = value }
            request.coverFileId.ifPresent { value -> it[Plans.coverFileId] = value }
        }
        Plans.selectAll().where { Plans.id eq id }.single().toPlan()
    }

    suspend fun complete(userId: UUID, roomId: UUID, id: UUID, request: CompletePlanRequest): Plan = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }
            .singleOrNull() ?: notFound()
        val note = request.completionNote.trim()
        validate { check(note.length in Limits.PLAN_LOG_LENGTH, "completionNote", "完成记录 1–10000 字") }
        if (row[Plans.status] == PlanStatus.Done.wireName) {
            if (row[Plans.completionNote] != note) throw ApiException(ProblemCode.ConflictVersion, "计划已有不同的完成记录")
        } else {
            writes.update(this, roomId, userId, EntityType.Plan, id, Plans) {
                it[Plans.status] = PlanStatus.Done.wireName
                it[Plans.completedAt] = writes.now()
                it[Plans.completionNote] = note
            }
        }
        Plans.selectAll().where { Plans.id eq id }.single().toPlan()
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Plan = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) }.singleOrNull() ?: notFound()
        if (row[Plans.deletedAt] == null) writes.softDelete(this, roomId, userId, EntityType.Plan, id, Plans)
        Plans.selectAll().where { Plans.id eq id }.single().toPlan()
    }

    private fun requirePlan(roomId: UUID, id: UUID) {
        if (Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.singleOrNull() == null) notFound()
    }

    suspend fun createStage(userId: UUID, roomId: UUID, planId: UUID, request: CreatePlanStageRequest): Pair<PlanStage, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        requirePlan(roomId, planId)
        val title = checkTitle(request.title)
        validate { check(request.sortOrder >= 0, "sortOrder", "不能小于 0") }
        val result = writes.create(this, roomId, userId, EntityType.PlanStage, request.id, PlanStages,
            { id -> PlanStages.selectAll().where { PlanStages.id eq id }.singleOrNull()?.toPlanStage() }) {
            it[PlanStages.planId] = planId; it[PlanStages.title] = title; it[PlanStages.sortOrder] = request.sortOrder
        }
        if (result.first.planId != planId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被别的计划使用")
        result
    }

    suspend fun updateStage(userId: UUID, roomId: UUID, planId: UUID, id: UUID, request: UpdatePlanStageRequest): PlanStage = db.tx {
        rooms.requireMember(roomId, userId)
        PlanStages.selectAll().where { (PlanStages.id eq id) and (PlanStages.roomId eq roomId) and (PlanStages.planId eq planId) and PlanStages.deletedAt.isNull() }
            .singleOrNull() ?: notFound()
        validate {
            check(listOf(request.title, request.sortOrder, request.doneAt).any { it.isPresent }, "body", "至少修改一个字段")
            request.sortOrder.ifPresent { check(it >= 0, "sortOrder", "不能小于 0") }
        }
        val title = (request.title as? Patch.Value)?.value?.let(::checkTitle)
        writes.update(this, roomId, userId, EntityType.PlanStage, id, PlanStages) {
            if (title != null) it[PlanStages.title] = title
            request.sortOrder.ifPresent { value -> it[PlanStages.sortOrder] = value }
            request.doneAt.ifPresent { value -> it[PlanStages.doneAt] = value }
        }
        PlanStages.selectAll().where { PlanStages.id eq id }.single().toPlanStage()
    }

    suspend fun deleteStage(userId: UUID, roomId: UUID, planId: UUID, id: UUID): PlanStage = db.tx {
        rooms.requireMember(roomId, userId)
        val row = PlanStages.selectAll().where { (PlanStages.id eq id) and (PlanStages.roomId eq roomId) and (PlanStages.planId eq planId) }
            .singleOrNull() ?: notFound()
        if (row[PlanStages.deletedAt] == null) writes.softDelete(this, roomId, userId, EntityType.PlanStage, id, PlanStages)
        PlanStages.selectAll().where { PlanStages.id eq id }.single().toPlanStage()
    }

    suspend fun createMilestone(userId: UUID, roomId: UUID, planId: UUID, request: CreateMilestoneRequest): Pair<Milestone, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        requirePlan(roomId, planId)
        val title = checkTitle(request.title)
        val result = writes.create(this, roomId, userId, EntityType.Milestone, request.id, Milestones,
            { id -> Milestones.selectAll().where { Milestones.id eq id }.singleOrNull()?.toMilestone() }) {
            it[Milestones.planId] = planId; it[Milestones.title] = title; it[Milestones.targetDate] = request.targetDate
        }
        if (result.first.planId != planId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被别的计划使用")
        result
    }

    suspend fun updateMilestone(userId: UUID, roomId: UUID, planId: UUID, id: UUID, request: UpdateMilestoneRequest): Milestone = db.tx {
        rooms.requireMember(roomId, userId)
        Milestones.selectAll().where { (Milestones.id eq id) and (Milestones.roomId eq roomId) and (Milestones.planId eq planId) and Milestones.deletedAt.isNull() }
            .singleOrNull() ?: notFound()
        validate { check(listOf(request.title, request.targetDate, request.doneAt).any { it.isPresent }, "body", "至少修改一个字段") }
        val title = (request.title as? Patch.Value)?.value?.let(::checkTitle)
        writes.update(this, roomId, userId, EntityType.Milestone, id, Milestones) {
            if (title != null) it[Milestones.title] = title
            request.targetDate.ifPresent { value -> it[Milestones.targetDate] = value }
            request.doneAt.ifPresent { value -> it[Milestones.doneAt] = value }
        }
        Milestones.selectAll().where { Milestones.id eq id }.single().toMilestone()
    }

    suspend fun deleteMilestone(userId: UUID, roomId: UUID, planId: UUID, id: UUID): Milestone = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Milestones.selectAll().where { (Milestones.id eq id) and (Milestones.roomId eq roomId) and (Milestones.planId eq planId) }
            .singleOrNull() ?: notFound()
        if (row[Milestones.deletedAt] == null) writes.softDelete(this, roomId, userId, EntityType.Milestone, id, Milestones)
        Milestones.selectAll().where { Milestones.id eq id }.single().toMilestone()
    }

    suspend fun createLog(userId: UUID, roomId: UUID, planId: UUID, request: CreatePlanLogRequest): Pair<PlanLog, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        requirePlan(roomId, planId)
        val body = request.body.trim()
        validate { check(body.length in Limits.PLAN_LOG_LENGTH, "body", "记录 1–10000 字") }
        val result = writes.create(this, roomId, userId, EntityType.PlanLog, request.id, PlanLogs,
            { id -> PlanLogs.selectAll().where { PlanLogs.id eq id }.singleOrNull()?.toPlanLog() }) {
            it[PlanLogs.planId] = planId; it[PlanLogs.authorId] = userId; it[PlanLogs.body] = body
        }
        if (result.first.planId != planId || result.first.authorId != userId) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
        result
    }
}
