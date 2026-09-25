package app.qichi.server.plans

import app.qichi.server.db.Milestones
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toPlan() = Plan(
    this[Plans.id], this[Plans.roomId], this[Plans.seq],
    this[Plans.createdAt], this[Plans.updatedAt], this[Plans.deletedAt], this[Plans.deletedBy],
    this[Plans.title], this[Plans.ownerId], fromWire<PlanStatus>(this[Plans.status]),
    this[Plans.targetDate], this[Plans.nextStep], this[Plans.nextStepOwnerId], this[Plans.nextStepDue],
    this[Plans.completedAt], this[Plans.completionNote], this[Plans.coverFileId],
)

fun ResultRow.toPlanStage() = PlanStage(
    this[PlanStages.id], this[PlanStages.roomId], this[PlanStages.seq],
    this[PlanStages.createdAt], this[PlanStages.updatedAt], this[PlanStages.deletedAt], this[PlanStages.deletedBy],
    this[PlanStages.planId], this[PlanStages.title], this[PlanStages.sortOrder], this[PlanStages.doneAt],
)

fun ResultRow.toMilestone() = Milestone(
    this[Milestones.id], this[Milestones.roomId], this[Milestones.seq],
    this[Milestones.createdAt], this[Milestones.updatedAt], this[Milestones.deletedAt], this[Milestones.deletedBy],
    this[Milestones.planId], this[Milestones.title], this[Milestones.targetDate], this[Milestones.doneAt],
)

fun ResultRow.toPlanLog() = PlanLog(
    this[PlanLogs.id], this[PlanLogs.roomId], this[PlanLogs.seq],
    this[PlanLogs.createdAt], this[PlanLogs.updatedAt], this[PlanLogs.deletedAt], this[PlanLogs.deletedBy],
    this[PlanLogs.planId], this[PlanLogs.authorId], this[PlanLogs.body],
)
