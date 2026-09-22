package app.qichi.shared.api

import app.qichi.shared.model.PlanStatus
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val title: String, val ownerId: Id, val status: PlanStatus,
    val targetDate: Day?, val nextStep: String?,
    val nextStepOwnerId: Id?, val nextStepDue: Day?,
    val completedAt: Timestamp?, val completionNote: String?,
) : SyncEntity

@Serializable
data class PlanStage(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val planId: Id, val title: String, val sortOrder: Int, val doneAt: Timestamp?,
) : SyncEntity

@Serializable
data class Milestone(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val planId: Id, val title: String, val targetDate: Day?, val doneAt: Timestamp?,
) : SyncEntity

/** 过程记录创建后不修改。 */
@Serializable
data class PlanLog(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val planId: Id, val authorId: Id, val body: String,
) : SyncEntity

@Serializable
data class PlanDetail(
    val plan: Plan,
    val stages: List<PlanStage>,
    val milestones: List<Milestone>,
    val logs: List<PlanLog>,
)

@Serializable
data class CreatePlanRequest(
    val id: Id, val title: String, val ownerId: Id,
    val targetDate: Day? = null, val nextStep: String? = null,
    val nextStepOwnerId: Id? = null, val nextStepDue: Day? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdatePlanRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownerId: Patch<Id> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val status: Patch<PlanStatus> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targetDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextStep: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextStepOwnerId: Patch<Id?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextStepDue: Patch<Day?> = Patch.Absent,
)

@Serializable data class CreatePlanStageRequest(val id: Id, val title: String, val sortOrder: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdatePlanStageRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sortOrder: Patch<Int> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val doneAt: Patch<Timestamp?> = Patch.Absent,
)

@Serializable data class CreateMilestoneRequest(val id: Id, val title: String, val targetDate: Day? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateMilestoneRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targetDate: Patch<Day?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val doneAt: Patch<Timestamp?> = Patch.Absent,
)

@Serializable data class CreatePlanLogRequest(val id: Id, val body: String)
@Serializable data class CompletePlanRequest(val completionNote: String)
