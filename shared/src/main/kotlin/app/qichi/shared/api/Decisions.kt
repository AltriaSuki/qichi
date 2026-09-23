package app.qichi.shared.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** 一位成员对这个决定的关注点。 */
@Serializable
data class DecisionConcern(val userId: Id, val text: String)

/**
 * 同步实体 decision：一个要一起做的决定——问题、备选、各自关注点、最终决定、复查日期。
 * [finalChoice] 为空表示还没定；[reviewDate] 到了在「今天」页提醒复查。
 */
@Serializable
data class Decision(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val question: String,
    val options: List<String>,
    /** 每人最多一条，只能改自己的 */
    val concerns: List<DecisionConcern>,
    val finalChoice: String?,
    val decidedAt: Timestamp?,
    val decidedBy: Id?,
    val reviewDate: Day?,
    val createdBy: Id,
) : SyncEntity

@Serializable
data class CreateDecisionRequest(
    val id: Id,
    val question: String,
    val options: List<String> = emptyList(),
    val reviewDate: Day? = null,
)

/**
 * 只发改动的字段。[myConcern] 只改自己那一条（空字符串或 null 表示删掉）；
 * [finalChoice] 设为非空 = 定下来（记下时间和人），设为 null = 重新考虑。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateDecisionRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val question: Patch<String> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val options: Patch<List<String>> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val myConcern: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val finalChoice: Patch<String?> = Patch.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reviewDate: Patch<Day?> = Patch.Absent,
)
