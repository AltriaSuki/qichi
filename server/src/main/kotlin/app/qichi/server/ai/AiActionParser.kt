package app.qichi.server.ai

import app.qichi.shared.api.AiActionDraft
import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.rules.Limits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 解析出的一个提议。 */
data class ParsedAction(val kind: AiActionKind, val draft: AiActionDraft)

/** 模型的回答拆成给人看的文字和动作草稿。 */
data class ParsedAnswer(val text: String, val actions: List<ParsedAction>)

/**
 * 从模型的回答里取出动作草稿（P8-02，提示词见 prompts/chat_answer.md）。模型在回答最后写：
 *
 *     <actions>
 *     [{"kind":"event","title":"出发去海边","date":"2026-09-26","time":"08:00","location":"东山岛"},
 *      {"kind":"todo","title":"带外套","assignee":"小迟","due_date":"2026-09-26"}]
 *     </actions>
 *
 * 这里把名字换成成员 id、计划名换成计划 id、「日期 + 时刻」按房间时区换成 UTC，长度按 [Limits] 截断；
 * 缺必要字段、看不懂的条目直接丢掉（宁可少提议，不给出建不成的卡片）。没有这一段就没有动作。
 * 计划相关的（P14-04）：加阶段、里程碑、进展、下一步要能认出是哪个进行中的计划；新建的计划和已有的同名时不提议。
 */
class AiActionParser(
    private val zone: ZoneId,
    /** 显示名 → 成员 id */
    private val members: Map<String, UUID>,
    /** 进行中的计划：标题 → id */
    private val plans: Map<String, UUID>,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(answer: String): ParsedAnswer {
        val start = answer.indexOf(OPEN)
        if (start < 0) return ParsedAnswer(answer.trim(), emptyList())
        val end = answer.indexOf(CLOSE, start).let { if (it < 0) answer.length else it }
        val block = answer.substring(start + OPEN.length, end)
        val text = (answer.substring(0, start) + answer.substring(minOf(answer.length, end + CLOSE.length))).trim()
        val items = runCatching {
            val arr = block.substring(block.indexOf('[').coerceAtLeast(0), (block.lastIndexOf(']') + 1).coerceAtLeast(0))
            json.parseToJsonElement(arr) as JsonArray
        }.getOrNull() ?: return ParsedAnswer(text, emptyList())
        val actions = items.mapNotNull { (it as? JsonObject)?.let(::toAction) }.take(Limits.AI_ACTIONS_MAX)
        return ParsedAnswer(text, actions)
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun time(s: String?): LocalTime? = s?.let {
        runCatching { LocalTime.parse(it.padStart(5, '0').take(5)) }.getOrNull()
    }

    private fun cut(s: String?, max: Int) = s?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.ifEmpty { null }

    private fun member(name: String?): UUID? {
        val n = name?.trim()?.ifEmpty { null } ?: return null
        return members[n] ?: members.entries.firstOrNull { (k, _) -> k.contains(n) || n.contains(k) }?.value
    }

    private fun plan(name: String?): UUID? {
        val n = name?.trim()?.ifEmpty { null } ?: return null
        return plans[n] ?: plans.entries.firstOrNull { (k, _) -> k.contains(n) || n.contains(k) }?.value
    }

    private fun toAction(o: JsonObject): ParsedAction? = when (o.str("kind")?.lowercase()) {
        "event" -> event(o)
        "todo" -> todo(o)
        "archive", "archive_item" -> archive(o)
        "idea" -> idea(o)
        "plan" -> newPlan(o)
        "plan_stage", "stage" -> planStage(o)
        "milestone" -> milestone(o)
        "plan_log", "progress" -> planLog(o)
        "next_step" -> nextStep(o)
        else -> null
    }

    private fun event(o: JsonObject): ParsedAction? {
        val title = cut(o.str("title"), Limits.EVENT_TITLE_LENGTH.last) ?: return null
        val day = date(o.str("date", "start_date")) ?: return null
        val endDay = date(o.str("end_date"))?.takeIf { !it.isBefore(day) }
        val note = cut(o.str("note", "body"), Limits.NOTE_MAX)
        val location = cut(o.str("location"), Limits.EVENT_LOCATION_MAX)
        val at = time(o.str("time", "start_time"))
        val draft = if (at == null) {
            AiActionDraft(title, note = note, location = location, allDay = true, startDate = day, endDate = endDay ?: day)
        } else {
            val starts = day.atTime(at).atZone(zone).toInstant()
            val ends = time(o.str("end_time"))?.let { (endDay ?: day).atTime(it).atZone(zone).toInstant() }
                ?.takeIf { it.isAfter(starts) } ?: starts.plus(Duration.ofHours(1))
            AiActionDraft(title, note = note, location = location, startsAt = starts, endsAt = ends)
        }
        return ParsedAction(AiActionKind.Event, draft)
    }

    private fun todo(o: JsonObject): ParsedAction? {
        val title = cut(o.str("title"), Limits.TODO_TITLE_LENGTH.last) ?: return null
        val day = date(o.str("due_date", "date"))
        val at = day?.let { d -> time(o.str("due_time", "time"))?.let { d.atTime(it).atZone(zone).toInstant() } }
        return ParsedAction(
            AiActionKind.Todo,
            AiActionDraft(
                title, note = cut(o.str("note", "body"), Limits.NOTE_MAX),
                assigneeId = member(o.str("assignee")), dueDate = if (at == null) day else null, dueAt = at, planId = plan(o.str("plan")),
            ),
        )
    }

    private fun archive(o: JsonObject): ParsedAction? {
        val title = cut(o.str("title"), Limits.ARCHIVE_TITLE_LENGTH.last) ?: return null
        val kind = when (o.str("type", "archive_kind")?.lowercase()) {
            "preference", "偏好" -> ArchiveKind.Preference
            "boundary", "界限", "边界" -> ArchiveKind.Boundary
            "concern", "顾虑", "担忧" -> ArchiveKind.Concern
            "milestone", "纪念", "里程碑" -> ArchiveKind.Milestone
            "decision", "决定" -> ArchiveKind.Decision
            else -> ArchiveKind.Consensus
        }
        val body = o.str("body", "note")?.trim()?.take(Limits.ARCHIVE_BODY_MAX)
        return ParsedAction(AiActionKind.ArchiveItem, AiActionDraft(title, note = body, archiveKind = kind))
    }

    private fun idea(o: JsonObject): ParsedAction? {
        val body = o.str("body", "title")?.trim()?.take(Limits.IDEA_BODY_LENGTH.last)?.ifEmpty { null } ?: return null
        return ParsedAction(AiActionKind.Idea, AiActionDraft(body))
    }

    // ── 计划相关（P14-04） ──

    private fun newPlan(o: JsonObject): ParsedAction? {
        val title = cut(o.str("title"), Limits.PLAN_TITLE_LENGTH.last) ?: return null
        if (plans.containsKey(title)) return null
        return ParsedAction(
            AiActionKind.Plan,
            AiActionDraft(
                title, assigneeId = member(o.str("owner", "assignee")), dueDate = date(o.str("target_date", "date")),
                nextStep = cut(o.str("next_step"), Limits.PLAN_STEP_LENGTH.last),
            ),
        )
    }

    private fun planStage(o: JsonObject): ParsedAction? {
        val planId = plan(o.str("plan")) ?: return null
        val title = cut(o.str("title"), Limits.PLAN_TITLE_LENGTH.last) ?: return null
        return ParsedAction(AiActionKind.PlanStage, AiActionDraft(title, planId = planId))
    }

    private fun milestone(o: JsonObject): ParsedAction? {
        val planId = plan(o.str("plan")) ?: return null
        val title = cut(o.str("title"), Limits.PLAN_TITLE_LENGTH.last) ?: return null
        return ParsedAction(AiActionKind.Milestone, AiActionDraft(title, planId = planId, dueDate = date(o.str("date", "target_date"))))
    }

    private fun planLog(o: JsonObject): ParsedAction? {
        val planId = plan(o.str("plan")) ?: return null
        val body = cut(o.str("body", "title"), PLAN_LOG_DRAFT_MAX) ?: return null
        return ParsedAction(AiActionKind.PlanLog, AiActionDraft(body, planId = planId))
    }

    private fun nextStep(o: JsonObject): ParsedAction? {
        val planId = plan(o.str("plan")) ?: return null
        val title = cut(o.str("title", "step", "next_step"), Limits.PLAN_STEP_LENGTH.last) ?: return null
        return ParsedAction(
            AiActionKind.NextStep,
            AiActionDraft(title, planId = planId, assigneeId = member(o.str("assignee", "owner")), dueDate = date(o.str("due_date", "date"))),
        )
    }

    companion object {
        const val OPEN = "<actions>"

        /** AI 提议的一笔进展最多这么长（草稿的正文上限，见 openapi AiActionDraft） */
        private const val PLAN_LOG_DRAFT_MAX = 2000
        const val CLOSE = "</actions>"

        private val citation = Regex("\\s?\\[\\d{1,4}]")

        /**
         * 边生成边显示时给人看的部分：去掉动作段（包括只来了一半的「<act」），也先去掉 [n]——
         * 最后存下的回答会把引用重新编号，中途显示的编号对不上。
         */
        fun visiblePart(soFar: String): String {
            var text = soFar
            val i = text.indexOf(OPEN)
            if (i >= 0) {
                text = text.substring(0, i)
            } else {
                (OPEN.length - 1 downTo 1).firstOrNull { k -> text.endsWith(OPEN.substring(0, k)) }?.let { k -> text = text.dropLast(k) }
            }
            text = text.replace(citation, "")
            // 结尾只来了一半的「[1」也先不显示
            text = text.replace(Regex("\\[\\d{0,4}$"), "")
            return text.trim()
        }
    }
}
