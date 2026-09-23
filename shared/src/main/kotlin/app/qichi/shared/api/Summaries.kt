package app.qichi.shared.api

import app.qichi.shared.model.SummaryKind
import kotlinx.serialization.Serializable

/**
 * 总结里引用的一条来源：[number] 对应正文里的「[n]」，[type] 是同步实体类型（message、decision、idea、plan、archive_item、mood），
 * [label] 是当时的一小段摘录（原记录删掉后仍能看出是什么）。
 */
@Serializable
data class SummarySource(
    val number: Int,
    val type: String,
    val id: Id,
    val label: String,
    val at: Timestamp,
)

/**
 * 同步实体 summary：一段时间的回顾（AI 派生）。正文是 Markdown，里面用「[n]」引用 [sources]。
 * 年度回顾（kind = year）由服务端每年 1 月 1 日生成，[locked] 为 true，不能删除。
 */
@Serializable
data class Summary(
    override val id: Id,
    val roomId: Id,
    override val seq: Long,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val deletedAt: Timestamp?,
    val deletedBy: Id?,
    val kind: SummaryKind,
    /** 按房间时区的日期，含首尾 */
    val rangeStart: Day,
    val rangeEnd: Day,
    val body: String,
    val sources: List<SummarySource>,
    val aiDerived: Boolean,
    val locked: Boolean,
    /** 谁请求的；年度回顾为空 */
    val requestedBy: Id?,
) : SyncEntity

/**
 * 生成一份总结 → 202。周、月按 [anchor] 所在的那一周（周一到周日）、那个月算；custom 用 [rangeStart]–[rangeEnd]（最长一年）。
 * 结果是一条 id = [jobId] 的总结。
 */
@Serializable
data class CreateSummaryRequest(
    val jobId: Id,
    val kind: SummaryKind,
    val anchor: Day? = null,
    val rangeStart: Day? = null,
    val rangeEnd: Day? = null,
)
