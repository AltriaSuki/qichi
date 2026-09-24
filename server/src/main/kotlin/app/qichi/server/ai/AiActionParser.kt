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

    companion object {
        const val OPEN = "<actions>"
        const val CLOSE = "</actions>"
    }
}
