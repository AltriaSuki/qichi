package app.qichi.server.summaries

import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.Decisions
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Moods
import app.qichi.server.db.Plans
import app.qichi.server.db.Summaries
import app.qichi.shared.api.Summary
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.SummaryKind
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun ResultRow.toSummary() = Summary(
    id = this[Summaries.id], roomId = this[Summaries.roomId], seq = this[Summaries.seq],
    createdAt = this[Summaries.createdAt], updatedAt = this[Summaries.updatedAt],
    deletedAt = this[Summaries.deletedAt], deletedBy = this[Summaries.deletedBy],
    kind = fromWire<SummaryKind>(this[Summaries.kind]), rangeStart = this[Summaries.rangeStart], rangeEnd = this[Summaries.rangeEnd],
    body = this[Summaries.body], sources = this[Summaries.sources], aiDerived = this[Summaries.aiDerived],
    locked = this[Summaries.locked], requestedBy = this[Summaries.requestedBy],
)

/** 给 AI 的一条素材：编号来源，以及写进提示词的那一行。 */
data class SourceLine(val source: SummarySource, val line: String)

/**
 * 收集一段时间（房间时区的日期，含首尾）里能回顾的事：聊天（抽样）、心情、定下的决定、灵感、完成的计划、档案。
 * 两个人的房间，量不大；聊天多时均匀抽样，免得提示词太长。
 */
object SummaryData {
    private const val MESSAGE_SAMPLE = 120
    private const val MOOD_MAX = 40
    private const val LINE_MAX = 120

    private fun cut(text: String, max: Int = LINE_MAX) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length <= max) it else it.take(max - 1) + "…" }

    fun gather(roomId: UUID, start: LocalDate, end: LocalDate, zone: ZoneId, names: Map<UUID, String>): List<SourceLine> {
        val from = start.atStartOfDay(zone).toInstant()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant()
        data class Raw(val type: EntityType, val id: UUID, val at: Instant, val who: UUID?, val what: String, val text: String)
        val raw = mutableListOf<Raw>()

        val messages = Messages.selectAll().where {
            (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() and Messages.authorId.isNotNull() and
                (Messages.kind inList listOf(MessageKind.Text.wireName, MessageKind.Image.wireName)) and (Messages.body neq "") and
                (Messages.createdAt greaterEq from) and (Messages.createdAt less until)
        }.map { Raw(EntityType.Message, it[Messages.id], it[Messages.createdAt], it[Messages.authorId], "聊天", it[Messages.body]) }.sortedBy { it.at }
        raw += if (messages.size <= MESSAGE_SAMPLE) messages else messages.indices.step(messages.size / MESSAGE_SAMPLE + 1).map { messages[it] }

        raw += Moods.selectAll().where { (Moods.roomId eq roomId) and Moods.deletedAt.isNull() and (Moods.createdAt greaterEq from) and (Moods.createdAt less until) }
            .map { Raw(EntityType.Mood, it[Moods.id], it[Moods.createdAt], it[Moods.authorId], "心情", "${it[Moods.label]} ${it[Moods.intensity]}/10" + (it[Moods.note]?.let { n -> "，$n" } ?: "")) }
            .takeLast(MOOD_MAX)
        raw += Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() and (Decisions.decidedAt greaterEq from) and (Decisions.decidedAt less until) }
            .map { Raw(EntityType.Decision, it[Decisions.id], it[Decisions.decidedAt]!!, it[Decisions.decidedBy], "定下", "${it[Decisions.question]} → ${it[Decisions.finalChoice]}") }
        raw += Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() and (Ideas.createdAt greaterEq from) and (Ideas.createdAt less until) }
            .map { Raw(EntityType.Idea, it[Ideas.id], it[Ideas.createdAt], it[Ideas.authorId], "灵感", it[Ideas.body]) }
        raw += Plans.selectAll().where {
            (Plans.roomId eq roomId) and Plans.deletedAt.isNull() and (Plans.status eq PlanStatus.Done.wireName) and (Plans.completedAt greaterEq from) and (Plans.completedAt less until)
        }.map { Raw(EntityType.Plan, it[Plans.id], it[Plans.completedAt]!!, it[Plans.ownerId], "完成计划", it[Plans.title] + (it[Plans.completionNote]?.let { n -> "：$n" } ?: "")) }
        raw += ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() and (ArchiveItems.updatedAt greaterEq from) and (ArchiveItems.updatedAt less until) }
            .map { Raw(EntityType.ArchiveItem, it[ArchiveItems.id], it[ArchiveItems.updatedAt], it[ArchiveItems.revisedBy], "档案", it[ArchiveItems.title]) }

        return raw.sortedBy { it.at }.mapIndexed { i, r ->
            val day = r.at.atZone(zone).toLocalDate()
            val label = cut(r.text, 80)
            SourceLine(
                SummarySource(i + 1, r.type.wireName, r.id, label, r.at),
                "[${i + 1}] ${day.monthValue}月${day.dayOfMonth}日 · ${r.who?.let(names::get) ?: "其中一人"} · ${r.what}：${cut(r.text)}",
            )
        }
    }

    private val citation = Regex("\\[(\\d{1,4})]")

    /** 正文里实际引用到的来源（按编号）。 */
    fun cited(body: String, all: List<SourceLine>): List<SummarySource> {
        val numbers = citation.findAll(body).map { it.groupValues[1].toInt() }.toSet()
        return all.map { it.source }.filter { it.number in numbers }
    }
}
