package app.qichi.server.ai

import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.Decisions
import app.qichi.server.db.Events
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Moods
import app.qichi.server.db.Plans
import app.qichi.server.db.Todos
import app.qichi.server.summaries.SourceLine
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 让 AI 知道「现在」和「你们」（docs/10-ai-assistant.md 第一步）。
 *
 * [now]：今天几号、星期几、几点、房间时区、房间里的人。
 * [gather]：按问题在房间里找资料，编号后给 AI，回答里用 [n] 标出依据。两类：
 *  - 常备的：接下来两周的日程、没做完的待办、进行中的计划、档案、最近定下的决定、最近三天的心情；
 *  - 按问题找的：问题里的词在决定、灵感、过去的日程、做完的待办、更早的聊天里出现过的。
 * 两个人的房间数据量小，除了聊天都直接读出来在内存里打分；聊天用 ILIKE（有 pg_trgm 索引）。
 * [prefs] 关掉的类别一条都不读。
 */
object RoomContext {
    private const val SOURCES_MAX = 30
    private const val LINE_MAX = 120
    private const val EVENTS_AHEAD_DAYS = 14L
    private const val MOOD_DAYS = 3L

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    internal fun weekday(d: DayOfWeek) = weekdays[d.value - 1]

    fun now(at: Instant, zone: ZoneId, names: Map<UUID, String>, askerId: UUID?): String {
        val t = at.atZone(zone)
        val people = names.entries.joinToString("、") { (id, name) -> if (id == askerId) "$name（提问的人）" else name }
        return "现在是 ${t.year}年${t.monthValue}月${t.dayOfMonth}日 ${weekday(t.dayOfWeek)} %02d:%02d（房间时区 %s）。".format(t.hour, t.minute, zone.id) +
            if (people.isEmpty()) "" else "\n房间里的人：$people。"
    }

    internal fun cut(text: String, max: Int = LINE_MAX) =
        text.replace(Regex("\\s+"), " ").trim().let { if (it.length <= max) it else it.take(max - 1) + "…" }

    internal fun day(d: LocalDate, today: LocalDate): String {
        val base = if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日" else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
        val rel = when (d) {
            today -> "，今天"
            today.plusDays(1) -> "，明天"
            today.minusDays(1) -> "，昨天"
            else -> ""
        }
        return "$base（${weekday(d.dayOfWeek)}$rel）"
    }

    internal fun time(t: ZonedDateTime, today: LocalDate) = day(t.toLocalDate(), today) + " %02d:%02d".format(t.hour, t.minute)

    // ── 问题里的词 ──

    /** 太常见、找不出东西的两字词。 */
    private val stopGrams = setOf(
        "我们", "你们", "他们", "什么", "怎么", "一下", "这个", "那个", "是不", "不是", "上次", "多少", "有没", "没有", "可以",
        "时候", "哪里", "哪个", "哪些", "一个", "还是", "就是", "觉得", "知道", "应该", "现在", "今天", "需要", "为什", "么时",
        "的时", "了吗", "的是", "是什", "么样", "有什", "要不", "不要", "一起", "帮我", "我想", "想要", "能不", "不能", "说好",
    )

    /** 中文按相邻两字切，英文和数字按词；去掉太常见的。 */
    fun terms(question: String): Set<String> {
        val out = linkedSetOf<String>()
        Regex("[\\p{IsHan}]+|[A-Za-z0-9]{2,}").findAll(question).forEach { m ->
            val w = m.value
            if (Character.UnicodeScript.of(w.first().code) != Character.UnicodeScript.HAN) {
                out += w.lowercase()
            } else if (w.length >= 2) {
                w.windowed(2).filterNot { it in stopGrams }.forEach { out += it }
            }
        }
        return out
    }

    internal fun score(text: String, terms: Set<String>): Int {
        if (terms.isEmpty()) return 0
        val t = text.lowercase()
        return terms.count { it in t }
    }

    // ── 收集 ──

    private data class Hit(
        val type: EntityType,
        val id: UUID,
        val at: Instant,
        val label: String,
        val line: String,
        /** 越大越靠前；常备的给一个底分，按问题找到的按匹配的词数 */
        val rank: Double,
    )

    fun gather(
        roomId: UUID,
        question: String,
        now: Instant,
        zone: ZoneId,
        names: Map<UUID, String>,
        prefs: AiPrefs,
        excludeMessageIds: Set<UUID> = emptySet(),
    ): List<SourceLine> {
        val terms = terms(question)
        val today = now.atZone(zone).toLocalDate()
        fun who(id: UUID?) = id?.let(names::get) ?: "其中一人"
        val hits = mutableListOf<Hit>()

        if (prefs.events) {
            val until = today.plusDays(EVENTS_AHEAD_DAYS)
            Events.selectAll().where { (Events.roomId eq roomId) and Events.deletedAt.isNull() }.forEach { r ->
                val start = r[Events.startsAt]?.atZone(zone)
                val startDay = start?.toLocalDate() ?: r[Events.startDate] ?: return@forEach
                val endDay = r[Events.endsAt]?.atZone(zone)?.toLocalDate() ?: r[Events.endDate] ?: startDay
                val title = r[Events.title]
                val text = listOfNotNull(title, r[Events.location], r[Events.note]).joinToString(" ")
                val upcoming = !endDay.isBefore(today) && !startDay.isAfter(until)
                val s = score(text, terms)
                if (!upcoming && s == 0) return@forEach
                val whenText = if (r[Events.allDay] || start == null) {
                    day(startDay, today) + (if (endDay != startDay) "—" + day(endDay, today) else "") + " 全天"
                } else {
                    time(start, today)
                }
                val line = "日程 · $whenText · $title" + (r[Events.location]?.let { " · 在$it" } ?: "") + (r[Events.note]?.let { " · $it" } ?: "")
                hits += Hit(EntityType.Event, r[Events.id], r[Events.startsAt] ?: startDay.atStartOfDay(zone).toInstant(), cut(title, 80), line,
                    (if (upcoming) 2.0 else 0.0) + s)
            }
        }
        if (prefs.todos) {
            Todos.selectAll().where { (Todos.roomId eq roomId) and Todos.deletedAt.isNull() }.forEach { r ->
                val open = r[Todos.doneAt] == null
                val text = r[Todos.title] + " " + (r[Todos.note] ?: "")
                val s = score(text, terms)
                if (!open && s == 0) return@forEach
                val due = r[Todos.dueAt]?.atZone(zone)?.let { time(it, today) + "前" } ?: r[Todos.dueDate]?.let { day(it, today) + "前" }
                val line = "待办 · ${r[Todos.title]}" +
                    (r[Todos.assigneeId]?.let { " · 给${who(it)}" } ?: "") +
                    (due?.let { " · $it" } ?: "") +
                    (if (open) " · 没做完" else " · ${who(r[Todos.doneBy])}已做完") +
                    (r[Todos.note]?.let { " · $it" } ?: "")
                hits += Hit(EntityType.Todo, r[Todos.id], r[Todos.createdAt], cut(r[Todos.title], 80), line,
                    (if (open) 1.5 else 0.0) + s)
            }
        }
        if (prefs.plans) {
            Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.forEach { r ->
                val active = r[Plans.status] == PlanStatus.Active.wireName
                val text = listOfNotNull(r[Plans.title], r[Plans.nextStep], r[Plans.completionNote]).joinToString(" ")
                val s = score(text, terms)
                if (!active && s == 0) return@forEach
                val line = "计划 · ${r[Plans.title]}（${who(r[Plans.ownerId])}负责" + (if (active) "，进行中" else "，已结束") + "）" +
                    (r[Plans.targetDate]?.let { " · 目标 ${day(it, today)}" } ?: "") +
                    (r[Plans.nextStep]?.let { ns -> " · 下一步：$ns" + (r[Plans.nextStepOwnerId]?.let { "（${who(it)}" + (r[Plans.nextStepDue]?.let { d -> "，${day(d, today)}前" } ?: "") + "）" } ?: "") } ?: "") +
                    (r[Plans.completionNote]?.let { " · 完成记录：$it" } ?: "")
                hits += Hit(EntityType.Plan, r[Plans.id], r[Plans.updatedAt], cut(r[Plans.title], 80), line, (if (active) 1.5 else 0.0) + s)
            }
        }
        if (prefs.archive) {
            ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }.forEach { r ->
                val kind = when (r[ArchiveItems.kind]) {
                    "preference" -> "偏好"; "consensus" -> "共识"; "decision" -> "决定"; "boundary" -> "界限"; "concern" -> "顾虑"; "milestone" -> "纪念"
                    else -> "档案"
                }
                val s = score(r[ArchiveItems.title] + " " + r[ArchiveItems.body], terms)
                val line = "档案 · $kind · ${r[ArchiveItems.title]}" + r[ArchiveItems.body].takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                hits += Hit(EntityType.ArchiveItem, r[ArchiveItems.id], r[ArchiveItems.updatedAt], cut(r[ArchiveItems.title], 80), line, 1.0 + s)
            }
        }
        if (prefs.decisions) {
            Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }
                .orderBy(Decisions.updatedAt, SortOrder.DESC).forEachIndexed { i, r ->
                    val decided = r[Decisions.finalChoice] != null
                    val s = score(r[Decisions.question] + " " + (r[Decisions.finalChoice] ?: "") + " " + r[Decisions.optionList].joinToString(" "), terms)
                    if (s == 0 && (!decided || i >= 5)) return@forEachIndexed
                    val at = r[Decisions.decidedAt] ?: r[Decisions.createdAt]
                    val line = if (decided) {
                        "决定（${day(at.atZone(zone).toLocalDate(), today)}定下）· ${r[Decisions.question]} → ${r[Decisions.finalChoice]}" +
                            (r[Decisions.reviewDate]?.let { " · ${day(it, today)}复查" } ?: "")
                    } else {
                        "还没定的决定 · ${r[Decisions.question]}" + r[Decisions.optionList].takeIf { it.isNotEmpty() }?.let { " · 选项：${it.joinToString("／")}" }.orEmpty()
                    }
                    hits += Hit(EntityType.Decision, r[Decisions.id], at, cut(r[Decisions.question], 80), line, (if (decided) 0.8 else 0.5) + s)
                }
        }
        if (prefs.ideas && terms.isNotEmpty()) {
            Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.forEach { r ->
                val s = score(r[Ideas.body], terms)
                if (s == 0) return@forEach
                val line = "灵感（${day(r[Ideas.createdAt].atZone(zone).toLocalDate(), today)}，${who(r[Ideas.authorId])}）· ${r[Ideas.body]}"
                hits += Hit(EntityType.Idea, r[Ideas.id], r[Ideas.createdAt], cut(r[Ideas.body], 80), line, s.toDouble())
            }
        }
        if (prefs.moods) {
            val from = today.minusDays(MOOD_DAYS - 1).atStartOfDay(zone).toInstant()
            Moods.selectAll().where { (Moods.roomId eq roomId) and Moods.deletedAt.isNull() and (Moods.createdAt greaterEq from) }
                .orderBy(Moods.createdAt, SortOrder.DESC).limit(6).forEach { r ->
                    val text = "${r[Moods.label]} ${r[Moods.intensity]}/10" + (r[Moods.note]?.let { "，$it" } ?: "")
                    val line = "心情（${time(r[Moods.createdAt].atZone(zone), today)}，${who(r[Moods.authorId])}）· ${MoodWords.line(r[Moods.label], r[Moods.intensity], r[Moods.note])}" +
                        (if (r[Moods.needsComfort]) " · 想被安慰" else "")
                    hits += Hit(EntityType.Mood, r[Moods.id], r[Moods.createdAt], cut(text, 80), line, 0.7 + score(text, terms))
                }
        }
        if (prefs.chat && terms.isNotEmpty()) {
            val grams = terms.sortedByDescending { it.length }.take(8)
            val anyTerm = grams.map<String, Op<Boolean>> { g -> Messages.body like "%${g.replace("%", "").replace("_", "")}%" }.reduce { a, b -> a or b }
            Messages.selectAll().where {
                (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() and Messages.authorId.isNotNull() and
                    (Messages.kind eq MessageKind.Text.wireName) and (Messages.body neq "") and
                    (if (excludeMessageIds.isEmpty()) Op.TRUE else (Messages.id notInList excludeMessageIds)) and anyTerm
            }.orderBy(Messages.createdSeq, SortOrder.DESC).limit(200)
                .map { r -> r to score(r[Messages.body], terms) }
                .sortedByDescending { it.second }.take(8)
                .forEach { (r, s) ->
                    val line = "聊天（${time(r[Messages.createdAt].atZone(zone), today)}，${who(r[Messages.authorId])}）：${r[Messages.body]}"
                    hits += Hit(EntityType.Message, r[Messages.id], r[Messages.createdAt], cut(r[Messages.body], 80), line, s.toDouble())
                }
        }

        return hits.sortedByDescending { it.rank }.take(SOURCES_MAX)
            .sortedWith(compareBy({ order(it.type) }, { it.at }))
            .mapIndexed { i, h ->
                SourceLine(SummarySource(i + 1, h.type.wireName, h.id, h.label, h.at), "[${i + 1}] ${cut(h.line)}")
            }
    }

    data class Cited(val body: String, val sources: List<SummarySource>)

    /**
     * 回答里引用到的来源按第一次出现的顺序重新编成 1、2、3……（给 AI 的编号是检索时排的，会跳号）；
     * 同一句里重复的编号只留一个，不存在的编号去掉。
     */
    fun renumber(text: String, all: List<SummarySource>): Cited {
        val byNumber = all.associateBy { it.number }
        val mapping = linkedMapOf<Int, Int>()
        val body = citation.replace(text) { m ->
            val n = m.groupValues[1].toInt()
            if (n !in byNumber) "" else "[" + mapping.getOrPut(n) { mapping.size + 1 } + "]"
        }.replace(Regex("(\\[\\d+])(\\1)+"), "$1").replace(Regex(" +([。，；！？])"), "$1").trim()
        return Cited(body, mapping.map { (old, new) -> byNumber.getValue(old).copy(number = new) })
    }

    private val citation = Regex("\\[(\\d{1,4})]")

    /** 给 AI 看时按类别分组：先是现在和接下来的事，再是长期的，最后是聊天。 */
    private fun order(type: EntityType) = when (type) {
        EntityType.Event -> 0
        EntityType.Todo -> 1
        EntityType.Plan -> 2
        EntityType.Mood -> 3
        EntityType.ArchiveItem -> 4
        EntityType.Decision -> 5
        EntityType.Idea -> 6
        else -> 7
    }
}
