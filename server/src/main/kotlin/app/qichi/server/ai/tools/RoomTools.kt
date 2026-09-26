package app.qichi.server.ai.tools

import app.qichi.server.ai.AiTool
import app.qichi.server.ai.AiToolCall
import app.qichi.server.ai.MoodWords
import app.qichi.server.ai.RoomContext
import app.qichi.server.db.AiFindings
import app.qichi.server.db.AnnotationReplies
import app.qichi.server.db.Annotations
import app.qichi.server.db.Answers
import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.BoardPosts
import app.qichi.server.db.BoardTopics
import app.qichi.server.db.Books
import app.qichi.server.db.Decisions
import app.qichi.server.db.DocComments
import app.qichi.server.db.DocumentVersions
import app.qichi.server.db.Documents
import app.qichi.server.db.Events
import app.qichi.server.db.Highlights
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Milestones
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.server.db.ReadingProgressTable
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.Summaries
import app.qichi.server.db.Todos
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.Message
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.rules.Tags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 问 AI 时给模型用的只读查询工具（P11-02，docs/10-ai-assistant.md 第七节）。
 *
 * - 只查本房间 [roomId]；删除的、撤回的一律不给。
 * - 没揭晓的问答回答不给（否则提问的人能借 AI 先看到对方的答案）；没公开的摘录不给（AI 的回答在两人共享的聊天里）。
 * - 「AI 能看什么」：[prefs] 是两个人都允许的类别（调用方算好交集），关掉的类别不提供工具，搜索也跳过。
 * - 结果里每条记录带 [n] 编号，和事先备料共用 [book]：AI 用编号引用，也用编号看详情。
 *
 * [run] 要在数据库事务里调用。
 */
class RoomTools(
    private val roomId: UUID,
    now: Instant,
    private val zone: ZoneId,
    private val names: Map<UUID, String>,
    private val prefs: AiPrefs,
    val book: SourceBook,
) {
    private val today: LocalDate = now.atZone(zone).toLocalDate()

    private class Spec(val tool: AiTool, val allowed: Boolean, val status: (Args) -> String, val run: (Args) -> String)

    /** 搜索能选的类别（只列允许的）。 */
    private val categories: List<String> = listOfNotNull(
        "chat".takeIf { prefs.chat }, "events".takeIf { prefs.events }, "todos".takeIf { prefs.todos }, "plans".takeIf { prefs.plans },
        "ideas".takeIf { prefs.ideas }, "archive".takeIf { prefs.archive }, "decisions".takeIf { prefs.decisions }, "moods".takeIf { prefs.moods },
        "qna".takeIf { prefs.qna }, "writing".takeIf { prefs.writing }, "board".takeIf { prefs.board }, "reading".takeIf { prefs.reading },
        "review".takeIf { prefs.review }, "summaries".takeIf { prefs.summaries },
    )

    private val specs: List<Spec> = listOf(
        Spec(
            AiTool(
                "search",
                "在房间里按关键词找，同时找所有类别。不知道东西在哪时先用它；结果带 [n] 编号，再用对应类别的工具加编号看详情。",
                schema("query" to strParam("关键词，几个字就够，如「民宿」「预算」"), "types" to listParam("只找这些类别，不写就是全部", categories), required = listOf("query")),
            ),
            categories.isNotEmpty(), { "搜索「${it.str("query").orEmpty()}」" }, ::search,
        ),
        Spec(
            AiTool(
                "read_chat",
                "读聊天记录（从早到晚）。给 from/to 读某几天的；给 around 读某条消息前后的；都不给就是最近的。who 只看某个人说的。",
                schema(
                    "from" to dateParam("从哪天"), "to" to dateParam("到哪天（含）"), "around" to intParam("看这条消息（编号 [n]）前后的"),
                    "who" to strParam("只看谁说的（名字）"), "limit" to intParam("最多几条，默认 40，最多 $CHAT_MAX"),
                ),
            ),
            prefs.chat,
            { a -> if (a.int("around") != null) "聊天（某条前后）" else a.date("from")?.let { "聊天 ${md(it)}–${md(a.date("to") ?: today)}" } ?: "最近的聊天" },
            ::readChat,
        ),
        Spec(
            AiTool("events", "列出某段日子里的日程（含全天的），按时间排。", schema("from" to dateParam("从哪天"), "to" to dateParam("到哪天（含）"), required = listOf("from", "to"))),
            prefs.events, { a -> "日历 ${a.date("from")?.let(::md).orEmpty()}–${a.date("to")?.let(::md).orEmpty()}" }, ::events,
        ),
        Spec(
            AiTool(
                "todos", "列出待办。默认只列没做完的。",
                schema(
                    "status" to strParam("open 没做完（默认）、done 做完的、all 全部", listOf("open", "done", "all")), "who" to strParam("交给谁的（名字）"),
                    "due_from" to dateParam("截止日从哪天"), "due_to" to dateParam("截止日到哪天"), "plan" to intParam("只看属于这个计划（编号 [n]）的"),
                ),
            ),
            prefs.todos, { "待办" }, ::todos,
        ),
        Spec(
            AiTool(
                "plans", "不给 ref：列出计划；给 ref：看这个计划的阶段、里程碑、进展记录和挂在下面的待办。",
                schema("status" to strParam("active 进行中（默认）、done 已结束、all 全部", listOf("active", "done", "all")), "ref" to refParam("计划")),
            ),
            prefs.plans, { a -> if (a.int("ref") != null) "计划详情" else "计划" }, ::plans,
        ),
        Spec(
            AiTool("ideas", "列出灵感，新的在前。可以按标签（如「旅行」，含子标签）或关键词筛。", schema("tag" to strParam("标签，不带 #"), "keyword" to strParam("关键词"), "limit" to intParam("最多几条，默认 30"))),
            prefs.ideas, { "灵感" }, ::ideas,
        ),
        Spec(
            AiTool(
                "archive", "列出档案：两个人记下的偏好、共识、决定、界限、顾虑、纪念。",
                schema("kind" to strParam("只看一种", listOf("preference", "consensus", "decision", "boundary", "concern", "milestone"))),
            ),
            prefs.archive, { "档案" }, ::archive,
        ),
        Spec(
            AiTool(
                "decisions", "不给 ref：列出决定；给 ref：看这个决定的备选、两个人各自在意的点、定了什么、哪天复查。",
                schema("status" to strParam("all 全部（默认）、decided 定了的、open 还没定的", listOf("all", "decided", "open")), "ref" to refParam("决定")),
            ),
            prefs.decisions, { a -> if (a.int("ref") != null) "决定详情" else "决定" }, ::decisions,
        ),
        Spec(
            AiTool("moods", "列出某段日子里的心情记录（含对方的回应），新的在前。", schema("from" to dateParam("从哪天"), "to" to dateParam("到哪天（含）"), "who" to strParam("只看谁的（名字）"), required = listOf("from", "to"))),
            prefs.moods, { a -> "心情 ${a.date("from")?.let(::md).orEmpty()}–${a.date("to")?.let(::md).orEmpty()}" }, ::moods,
        ),
        Spec(
            AiTool("qna", "列出每日问答：题目，以及两个人都确认后揭晓的回答（没揭晓的回答看不到）。默认最近 30 天。", schema("from" to dateParam("从哪天"), "to" to dateParam("到哪天（含）"))),
            prefs.qna, { "问答" }, ::qna,
        ),
        Spec(
            AiTool(
                "documents", "不给 ref：列出文稿；给 ref：读这篇文稿已保存的最新版正文（很长时分段，用 offset 接着读）和段落旁的留言。",
                schema("ref" to refParam("文稿"), "offset" to intParam("从第几个字开始读，默认 0"), "category" to strParam("只看一类（列表时）")),
            ),
            prefs.writing, { a -> if (a.int("ref") != null) "文稿正文" else "文稿" }, ::documents,
        ),
        Spec(
            AiTool("board", "不给 ref：列出留言板的话题；给 ref：读这个话题下的所有留言和回复。", schema("ref" to refParam("话题"))),
            prefs.board, { a -> if (a.int("ref") != null) "留言" else "留言板" }, ::board,
        ),
        Spec(
            AiTool("reading", "不给 ref：列出书架上的书和两个人的进度；给 ref：看这本书的进度和两个人公开的摘录、划线、感想。", schema("ref" to refParam("书"))),
            prefs.reading, { a -> if (a.int("ref") != null) "书里的摘录" else "书架" }, ::reading,
        ),
        Spec(
            AiTool("review", "不给 ref：列出审稿的文件；给 ref：看这份文件的批注、讨论和 AI 审稿发现。", schema("ref" to refParam("审稿文件"))),
            prefs.review, { a -> if (a.int("ref") != null) "审稿批注" else "审稿" }, ::review,
        ),
        Spec(
            AiTool("summaries", "不给 ref：列出已有的周报、月报、年度回顾；给 ref：读这份总结的正文。", schema("ref" to refParam("总结"))),
            prefs.summaries, { a -> if (a.int("ref") != null) "总结正文" else "总结" }, ::summaries,
        ),
    )

    /** 这次可以提供给模型的工具（关掉的类别不提供）。 */
    val definitions: List<AiTool> get() = specs.filter { it.allowed }.map { it.tool }

    /** 给人看的「正在查：……」。 */
    fun status(call: AiToolCall): String =
        specs.firstOrNull { it.tool.name == call.name }?.let { s -> runCatching { s.status(Args.parse(call.arguments)) }.getOrNull() } ?: "资料"

    /** 执行一次工具调用，返回给模型看的文字。参数不对、没有这个工具时也返回文字（让模型改了再查），不抛异常。 */
    fun run(call: AiToolCall): String {
        val spec = specs.firstOrNull { it.tool.name == call.name && it.allowed } ?: return "没有这个工具：${call.name}"
        return try {
            spec.run(Args.parse(call.arguments))
        } catch (e: BadArgs) {
            "参数不对：${e.message}"
        }
    }

    // ── 小工具 ──

    private fun who(id: UUID?) = id?.let(names::get) ?: "其中一人"
    private fun day(d: LocalDate) = RoomContext.day(d, today)
    private fun time(i: Instant) = RoomContext.time(i.atZone(zone), today)
    private fun dateOf(i: Instant): LocalDate = i.atZone(zone).toLocalDate()
    private fun startOf(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
    private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    private fun cut(text: String, max: Int = LINE_MAX) = RoomContext.cut(text, max)

    /** 参数里的编号换成那条记录的 id；编号不存在或类别不对时告诉模型。 */
    private fun ref(a: Args, type: EntityType, name: String = "ref"): UUID? {
        val n = a.int(name) ?: return null
        val src = book[n] ?: throw BadArgs("没有编号 [$n]，先查出来再用编号")
        if (src.type != type.wireName) throw BadArgs("[$n] 是${typeName(src.type)}，不是${typeName(type.wireName)}")
        return src.id
    }

    private fun person(a: Args, name: String = "who"): UUID? = a.str(name)?.let { n ->
        names.entries.firstOrNull { it.value == n }?.key
            ?: names.entries.firstOrNull { n in it.value || it.value in n }?.key
            ?: throw BadArgs("房间里没有「$n」，可以是：${names.values.joinToString("、")}")
    }

    /** from/to 两个日期，to 不早于 from，跨度不超过 [maxDays]。 */
    private fun range(a: Args, maxDays: Long): Pair<LocalDate, LocalDate> {
        val from = a.requireDate("from")
        val to = a.requireDate("to")
        if (to.isBefore(from)) throw BadArgs("to 不能早于 from")
        if (ChronoUnit.DAYS.between(from, to) > maxDays) throw BadArgs("一次最多查 $maxDays 天，请分几次查")
        return from to to
    }

    /** 长文里关键词附近的一小段。 */
    private fun snippet(text: String, terms: Set<String>, max: Int = 100): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val lower = flat.lowercase()
        val at = terms.mapNotNull { t -> lower.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - 30).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }


    // ── 每一类的一行 ──

    private fun eventLine(r: ResultRow): String {
        val start = r[Events.startsAt]
        val startDay = start?.let(::dateOf) ?: r[Events.startDate]
        val endDay = r[Events.endsAt]?.let(::dateOf) ?: r[Events.endDate] ?: startDay
        val whenText = when {
            startDay == null -> "没有日期"
            r[Events.allDay] || start == null -> day(startDay) + (if (endDay != null && endDay != startDay) "—" + day(endDay) else "") + " 全天"
            else -> time(start) + (r[Events.endsAt]?.atZone(zone)?.let { e -> "–%02d:%02d".format(e.hour, e.minute) } ?: "")
        }
        return "日程 · $whenText · ${r[Events.title]}" + (r[Events.location]?.let { " · 在$it" } ?: "") + (r[Events.note]?.let { " · ${cut(it, 120)}" } ?: "")
    }

    private fun todoLine(r: ResultRow, planTitles: Map<UUID, String>, parentTitles: Map<UUID, String>): String {
        val due = r[Todos.dueAt]?.let { time(it) + "前" } ?: r[Todos.dueDate]?.let { day(it) + "前" }
        return "待办 · ${r[Todos.title]}" +
            (r[Todos.parentId]?.let(parentTitles::get)?.let { "（属于「$it」）" } ?: "") +
            (r[Todos.assigneeId]?.let { " · 给${who(it)}" } ?: "") +
            (due?.let { " · $it" } ?: "") +
            (r[Todos.recurrence]?.let { " · 重复：$it" } ?: "") +
            (r[Todos.planId]?.let(planTitles::get)?.let { " · 计划「$it」" } ?: "") +
            (r[Todos.doneAt]?.let { " · ${who(r[Todos.doneBy])}${day(dateOf(it))}做完" } ?: " · 没做完") +
            (r[Todos.note]?.let { " · ${cut(it, 120)}" } ?: "")
    }

    private fun planLine(r: ResultRow): String {
        val active = r[Plans.status] == PlanStatus.Active.wireName
        return "计划 · ${r[Plans.title]}（${who(r[Plans.ownerId])}负责，" + (if (active) "进行中" else "已结束") + "）" +
            (r[Plans.targetDate]?.let { " · 目标 ${day(it)}" } ?: "") +
            (r[Plans.nextStep]?.let { ns -> " · 下一步：$ns" + (r[Plans.nextStepOwnerId]?.let { "（${who(it)}" + (r[Plans.nextStepDue]?.let { d -> "，${day(d)}前" } ?: "") + "）" } ?: "") } ?: "") +
            (r[Plans.completedAt]?.let { " · ${day(dateOf(it))}完成" } ?: "") +
            (r[Plans.completionNote]?.let { " · 完成记录：${cut(it, 150)}" } ?: "")
    }

    private fun archiveKind(kind: String) = when (kind) {
        "preference" -> "偏好"; "consensus" -> "共识"; "decision" -> "决定"; "boundary" -> "界限"; "concern" -> "顾虑"; "milestone" -> "纪念"
        else -> "档案"
    }

    private fun archiveLine(r: ResultRow) =
        "档案 · ${archiveKind(r[ArchiveItems.kind])} · ${r[ArchiveItems.title]}" + r[ArchiveItems.body].takeIf { it.isNotBlank() }?.let { "：${cut(it, 200)}" }.orEmpty() +
            "（${who(r[ArchiveItems.revisedBy])}，${day(dateOf(r[ArchiveItems.updatedAt]))}）"

    private fun decisionLine(r: ResultRow): String {
        val choice = r[Decisions.finalChoice]
        return if (choice != null) {
            "决定 · ${r[Decisions.question]} → 定了「$choice」（${r[Decisions.decidedAt]?.let { day(dateOf(it)) } ?: ""}）" + (r[Decisions.reviewDate]?.let { " · ${day(it)}复查" } ?: "")
        } else {
            "还没定的决定 · ${r[Decisions.question]}" + r[Decisions.optionList].takeIf { it.isNotEmpty() }?.let { " · 备选：${it.joinToString("／")}" }.orEmpty()
        }
    }

    private fun moodLine(r: ResultRow, replies: List<String>) =
        "心情（${time(r[Moods.createdAt])}，${who(r[Moods.authorId])}）· ${MoodWords.line(r[Moods.label], r[Moods.intensity], r[Moods.note])}" +
            (if (r[Moods.needsComfort]) " · 想被安慰" else "") +
            (if (replies.isNotEmpty()) " · 回应：${replies.joinToString("、")}" else "")

    private fun replyWord(kind: String) = when (kind) {
        "here" -> "我在这里"; "hug" -> "给你一个拥抱"; "ready" -> "等你准备好"
        else -> kind
    }

    private fun messageText(m: Message): String? =
        (if (m.kind == MessageKind.Ai) m.body else MessageRules.replyExcerpt(m.kind, m.body, m.file?.fileName, false))?.takeIf { it.isNotBlank() }

    private fun messageLine(m: Message, text: String) = "聊天（${time(m.createdAt)}，${m.authorId?.let(::who) ?: "AI"}）：${cut(text, 300)}"

    // ── 搜索 ──

    private class Hit(val score: Int, val at: Instant, val emit: (Out) -> Unit)

    private fun search(a: Args): String {
        val q = a.str("query") ?: throw BadArgs("缺少 query")
        val terms = RoomContext.terms(q).ifEmpty { setOf(q.lowercase()) }
        val want = a.strings("types").filter { it in categories }.toSet().ifEmpty { categories.toSet() }
        val hits = mutableListOf<Hit>()
        fun hit(text: String, at: Instant, emit: (Out, String) -> Unit) {
            val s = RoomContext.score(text, terms)
            if (s > 0) hits += Hit(s, at) { out -> emit(out, snippet(text, terms)) }
        }
        fun live(deleted: org.jetbrains.exposed.v1.core.Column<Instant?>) = deleted.isNull()

        if ("chat" in want) {
            val grams = terms.sortedByDescending { it.length }.take(8)
            val anyTerm = grams.map<String, Op<Boolean>> { g -> Messages.body like "%${g.replace("%", "").replace("_", "")}%" }.reduce { x, y -> x or y }
            messageQuery().where { (Messages.roomId eq roomId) and live(Messages.deletedAt) and Messages.retractedAt.isNull() and (Messages.body neq "") and anyTerm }
                .orderBy(Messages.createdSeq, SortOrder.DESC).limit(SEARCH_SCAN).map { it.toMessage() }
                .forEach { m -> val t = messageText(m) ?: return@forEach; hit(t, m.createdAt) { out, sn -> out.item(EntityType.Message, m.id, cut(t, 80), m.createdAt, messageLine(m, sn)) } }
        }
        if ("events" in want) {
            Events.selectAll().where { (Events.roomId eq roomId) and live(Events.deletedAt) }.forEach { r ->
                val at = r[Events.startsAt] ?: r[Events.startDate]?.let(::startOf) ?: r[Events.createdAt]
                hit(listOfNotNull(r[Events.title], r[Events.location], r[Events.note]).joinToString(" "), at) { out, _ -> out.item(EntityType.Event, r[Events.id], cut(r[Events.title], 80), at, eventLine(r)) }
            }
        }
        if ("todos" in want) {
            Todos.selectAll().where { (Todos.roomId eq roomId) and live(Todos.deletedAt) }.forEach { r ->
                hit(r[Todos.title] + " " + (r[Todos.note] ?: ""), r[Todos.createdAt]) { out, _ -> out.item(EntityType.Todo, r[Todos.id], cut(r[Todos.title], 80), r[Todos.createdAt], todoLine(r, emptyMap(), emptyMap())) }
            }
        }
        if ("plans" in want) {
            val logs = PlanLogs.selectAll().where { (PlanLogs.roomId eq roomId) and live(PlanLogs.deletedAt) }.groupBy({ it[PlanLogs.planId] }, { it[PlanLogs.body] })
            Plans.selectAll().where { (Plans.roomId eq roomId) and live(Plans.deletedAt) }.forEach { r ->
                val text = listOfNotNull(r[Plans.title], r[Plans.nextStep], r[Plans.completionNote]).joinToString(" ") + " " + logs[r[Plans.id]].orEmpty().joinToString(" ")
                hit(text, r[Plans.updatedAt]) { out, _ -> out.item(EntityType.Plan, r[Plans.id], cut(r[Plans.title], 80), r[Plans.updatedAt], planLine(r)) }
            }
        }
        if ("ideas" in want) {
            Ideas.selectAll().where { (Ideas.roomId eq roomId) and live(Ideas.deletedAt) }.forEach { r ->
                hit(r[Ideas.body], r[Ideas.createdAt]) { out, sn -> out.item(EntityType.Idea, r[Ideas.id], cut(r[Ideas.body], 80), r[Ideas.createdAt], "灵感（${day(dateOf(r[Ideas.createdAt]))}，${who(r[Ideas.authorId])}）· $sn") }
            }
        }
        if ("archive" in want) {
            ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and live(ArchiveItems.deletedAt) }.forEach { r ->
                hit(r[ArchiveItems.title] + " " + r[ArchiveItems.body], r[ArchiveItems.updatedAt]) { out, _ -> out.item(EntityType.ArchiveItem, r[ArchiveItems.id], cut(r[ArchiveItems.title], 80), r[ArchiveItems.updatedAt], archiveLine(r)) }
            }
        }
        if ("decisions" in want) {
            Decisions.selectAll().where { (Decisions.roomId eq roomId) and live(Decisions.deletedAt) }.forEach { r ->
                val text = r[Decisions.question] + " " + (r[Decisions.finalChoice] ?: "") + " " + r[Decisions.optionList].joinToString(" ") + " " + r[Decisions.concerns].joinToString(" ") { it.text }
                hit(text, r[Decisions.updatedAt]) { out, _ -> out.item(EntityType.Decision, r[Decisions.id], cut(r[Decisions.question], 80), r[Decisions.updatedAt], decisionLine(r)) }
            }
        }
        if ("moods" in want) {
            Moods.selectAll().where { (Moods.roomId eq roomId) and live(Moods.deletedAt) and Moods.note.isNotNull() }.forEach { r ->
                hit(MoodWords.of(r[Moods.label]) + " " + r[Moods.note], r[Moods.createdAt]) { out, _ ->
                    out.item(EntityType.Mood, r[Moods.id], cut(r[Moods.note].orEmpty(), 80), r[Moods.createdAt], moodLine(r, emptyList()))
                }
            }
        }
        if ("qna" in want) {
            qnaRows(null).forEach { row -> hit(row.question + " " + row.answers.joinToString(" "), row.at) { out, _ -> out.item(EntityType.QnaRound, row.id, cut(row.question, 80), row.at, row.line) } }
        }
        if ("writing" in want) {
            latestBodies().forEach { (r, body) ->
                hit(r[Documents.title] + " " + body, r[Documents.updatedAt]) { out, sn -> out.item(EntityType.Document, r[Documents.id], cut(r[Documents.title], 80), r[Documents.updatedAt], "文稿《${r[Documents.title]}》· $sn") }
            }
            val docs = Documents.selectAll().where { (Documents.roomId eq roomId) and live(Documents.deletedAt) }.associateBy { it[Documents.id] }
            DocComments.selectAll().where { (DocComments.roomId eq roomId) and live(DocComments.deletedAt) }.forEach { c ->
                val doc = docs[c[DocComments.documentId]] ?: return@forEach
                hit((c[DocComments.quote] ?: "") + " " + c[DocComments.body], c[DocComments.createdAt]) { out, sn ->
                    out.item(EntityType.Document, doc[Documents.id], cut(doc[Documents.title], 80), doc[Documents.updatedAt], "文稿《${doc[Documents.title]}》里${who(c[DocComments.authorId])}的留言：$sn")
                }
            }
        }
        if ("board" in want) {
            val topics = BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and live(BoardTopics.deletedAt) }.associateBy { it[BoardTopics.id] }
            topics.values.forEach { t -> hit(t[BoardTopics.title], t[BoardTopics.createdAt]) { out, _ -> out.item(EntityType.BoardTopic, t[BoardTopics.id], cut(t[BoardTopics.title], 80), t[BoardTopics.createdAt], "留言板话题《${t[BoardTopics.title]}》（${who(t[BoardTopics.authorId])}）") } }
            BoardPosts.selectAll().where { (BoardPosts.roomId eq roomId) and live(BoardPosts.deletedAt) }.forEach { p ->
                val t = topics[p[BoardPosts.topicId]] ?: return@forEach
                hit(p[BoardPosts.body], p[BoardPosts.createdAt]) { out, sn ->
                    out.item(EntityType.BoardTopic, t[BoardTopics.id], cut(t[BoardTopics.title], 80), t[BoardTopics.createdAt], "留言板《${t[BoardTopics.title]}》· ${who(p[BoardPosts.authorId])}（${day(dateOf(p[BoardPosts.createdAt]))}）：$sn")
                }
            }
        }
        if ("reading" in want) {
            val books = Books.selectAll().where { (Books.roomId eq roomId) and live(Books.deletedAt) }.associateBy { it[Books.id] }
            books.values.forEach { b -> hit(b[Books.title] + " " + (b[Books.author] ?: ""), b[Books.createdAt]) { out, _ -> out.item(EntityType.Book, b[Books.id], cut(b[Books.title], 80), b[Books.createdAt], "书《${b[Books.title]}》" + (b[Books.author]?.let { "（$it）" } ?: "")) } }
            sharedHighlights(null).forEach { h ->
                val b = books[h[Highlights.bookId]] ?: return@forEach
                hit(h[Highlights.text] + " " + (h[Highlights.note] ?: ""), h[Highlights.createdAt]) { out, _ ->
                    out.item(EntityType.Book, b[Books.id], cut(b[Books.title], 80), b[Books.createdAt], "《${b[Books.title]}》里${highlightLine(h)}")
                }
            }
        }
        if ("review" in want) {
            val docs = ReviewDocuments.selectAll().where { (ReviewDocuments.roomId eq roomId) and live(ReviewDocuments.deletedAt) }.associateBy { it[ReviewDocuments.id] }
            docs.values.forEach { d -> hit(d[ReviewDocuments.title], d[ReviewDocuments.updatedAt]) { out, _ -> out.item(EntityType.ReviewDocument, d[ReviewDocuments.id], cut(d[ReviewDocuments.title], 80), d[ReviewDocuments.updatedAt], "审稿文件《${d[ReviewDocuments.title]}》") } }
            Annotations.selectAll().where { (Annotations.roomId eq roomId) and live(Annotations.deletedAt) }.forEach { n ->
                val d = docs[n[Annotations.documentId]] ?: return@forEach
                hit(n[Annotations.body], n[Annotations.createdAt]) { out, sn ->
                    out.item(EntityType.ReviewDocument, d[ReviewDocuments.id], cut(d[ReviewDocuments.title], 80), d[ReviewDocuments.updatedAt], "审稿《${d[ReviewDocuments.title]}》里${who(n[Annotations.authorId])}的批注：$sn")
                }
            }
        }
        if ("summaries" in want) {
            Summaries.selectAll().where { (Summaries.roomId eq roomId) and live(Summaries.deletedAt) }.forEach { r ->
                val body = stripCitations(r[Summaries.body])
                hit(body, r[Summaries.createdAt]) { out, sn -> out.item(EntityType.Summary, r[Summaries.id], summaryTitle(r), r[Summaries.createdAt], "总结《${summaryTitle(r)}》· $sn") }
            }
        }

        val out = Out(book)
        val ranked = hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.at })
        ranked.take(SEARCH_MAX).forEach { it.emit(out) }
        if (ranked.size > SEARCH_MAX) out.more(ranked.size - SEARCH_MAX)
        return out.result("没有找到和「$q」有关的内容")
    }

    // ── 聊天 ──

    private fun readChat(a: Args): String {
        val limit = (a.int("limit") ?: 40).coerceIn(1, CHAT_MAX)
        val author = person(a)
        fun base() = messageQuery().where {
            (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() and
                (if (author != null) Messages.authorId eq author else Op.TRUE)
        }
        val around = ref(a, EntityType.Message, "around")
        var more = 0
        val rows: List<Message> = when {
            around != null -> {
                val seq = Messages.select(Messages.createdSeq).where { (Messages.id eq around) and (Messages.roomId eq roomId) }.singleOrNull()?.get(Messages.createdSeq)
                    ?: throw BadArgs("这条消息找不到了")
                val before = base().andWhere { Messages.createdSeq lessEq seq }.orderBy(Messages.createdSeq, SortOrder.DESC).limit(limit / 2 + 1).map { it.toMessage() }.reversed()
                val after = base().andWhere { Messages.createdSeq greater seq }.orderBy(Messages.createdSeq, SortOrder.ASC).limit(limit / 2).map { it.toMessage() }
                before + after
            }
            a.str("from") != null || a.str("to") != null -> {
                val (from, to) = range(a, 366)
                val q = base().andWhere { (Messages.createdAt greaterEq startOf(from)) and (Messages.createdAt less startOf(to.plusDays(1))) }
                val total = q.count()
                more = (total - limit).coerceAtLeast(0).toInt()
                q.orderBy(Messages.createdSeq, SortOrder.ASC).limit(limit).map { it.toMessage() }
            }
            else -> base().orderBy(Messages.createdSeq, SortOrder.DESC).limit(limit).map { it.toMessage() }.reversed()
        }
        val out = Out(book)
        rows.forEach { m -> messageText(m)?.let { t -> out.item(EntityType.Message, m.id, cut(t, 80), m.createdAt, messageLine(m, t)) } }
        out.more(more)
        return out.result("这段时间没有聊天")
    }

    // ── 日程、待办、计划 ──

    private fun events(a: Args): String {
        val (from, to) = range(a, 400)
        val out = Out(book)
        Events.selectAll().where { (Events.roomId eq roomId) and Events.deletedAt.isNull() }.mapNotNull { r ->
            val start = r[Events.startsAt]
            val startDay = start?.let(::dateOf) ?: r[Events.startDate] ?: return@mapNotNull null
            val endDay = r[Events.endsAt]?.let(::dateOf) ?: r[Events.endDate] ?: startDay
            if (endDay.isBefore(from) || startDay.isAfter(to)) null else r to (start ?: startOf(startDay))
        }.sortedBy { it.second }.forEach { (r, at) -> out.item(EntityType.Event, r[Events.id], cut(r[Events.title], 80), at, eventLine(r)) }
        return out.result("${day(from)}到${day(to)}没有日程")
    }

    private fun todos(a: Args): String {
        val status = a.choice("status", listOf("open", "done", "all"), "open")
        val assignee = person(a)
        val dueFrom = a.date("due_from")
        val dueTo = a.date("due_to")
        val plan = ref(a, EntityType.Plan, "plan")
        val all = Todos.selectAll().where { (Todos.roomId eq roomId) and Todos.deletedAt.isNull() }.toList()
        val titles = all.associate { it[Todos.id] to it[Todos.title] }
        val planTitles = Plans.select(Plans.id, Plans.title).where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.associate { it[Plans.id] to it[Plans.title] }
        val out = Out(book)
        all.filter { r ->
            val done = r[Todos.doneAt] != null
            val due = r[Todos.dueAt]?.let(::dateOf) ?: r[Todos.dueDate]
            (status == "all" || (status == "done") == done) &&
                (assignee == null || r[Todos.assigneeId] == assignee) &&
                (plan == null || r[Todos.planId] == plan) &&
                (dueFrom == null || (due != null && !due.isBefore(dueFrom))) &&
                (dueTo == null || (due != null && !due.isAfter(dueTo)))
        }.sortedWith(compareBy<ResultRow>({ it[Todos.dueAt]?.let(::dateOf) ?: it[Todos.dueDate] ?: LocalDate.MAX }, { it[Todos.createdAt] }))
            .let { if (status == "done") it.sortedByDescending { r -> r[Todos.doneAt] } else it }
            .forEach { r -> out.item(EntityType.Todo, r[Todos.id], cut(r[Todos.title], 80), r[Todos.createdAt], todoLine(r, planTitles, titles)) }
        return out.result("没有符合条件的待办")
    }

    private fun plans(a: Args): String {
        val id = ref(a, EntityType.Plan)
        val out = Out(book)
        if (id == null) {
            val status = a.choice("status", listOf("active", "done", "all"), "active")
            Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.orderBy(Plans.updatedAt, SortOrder.DESC)
                .filter { status == "all" || (it[Plans.status] == PlanStatus.Active.wireName) == (status == "active") }
                .forEach { r -> out.item(EntityType.Plan, r[Plans.id], cut(r[Plans.title], 80), r[Plans.updatedAt], planLine(r)) }
            return out.result("没有符合条件的计划")
        }
        val r = Plans.selectAll().where { (Plans.id eq id) and (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.singleOrNull() ?: return "这个计划已经删掉了"
        out.item(EntityType.Plan, id, cut(r[Plans.title], 80), r[Plans.updatedAt], planLine(r))
        val stages = PlanStages.selectAll().where { (PlanStages.planId eq id) and (PlanStages.roomId eq roomId) and PlanStages.deletedAt.isNull() }.orderBy(PlanStages.sortOrder).toList()
        if (stages.isNotEmpty()) out.text("阶段：" + stages.joinToString(" → ") { s -> s[PlanStages.title] + if (s[PlanStages.doneAt] != null) "（完成）" else "" })
        Milestones.selectAll().where { (Milestones.planId eq id) and (Milestones.roomId eq roomId) and Milestones.deletedAt.isNull() }.orderBy(Milestones.targetDate).forEach { m ->
            out.text("里程碑：${m[Milestones.title]}" + (m[Milestones.targetDate]?.let { " · ${day(it)}" } ?: "") + (if (m[Milestones.doneAt] != null) " · 完成" else ""))
        }
        Todos.selectAll().where { (Todos.planId eq id) and (Todos.roomId eq roomId) and Todos.deletedAt.isNull() }.orderBy(Todos.createdAt).forEach { t ->
            out.item(EntityType.Todo, t[Todos.id], cut(t[Todos.title], 80), t[Todos.createdAt], todoLine(t, emptyMap(), emptyMap()))
        }
        PlanLogs.selectAll().where { (PlanLogs.planId eq id) and (PlanLogs.roomId eq roomId) and PlanLogs.deletedAt.isNull() }.orderBy(PlanLogs.createdAt, SortOrder.DESC).limit(20).forEach { l ->
            out.text("进展（${day(dateOf(l[PlanLogs.createdAt]))}，${who(l[PlanLogs.authorId])}）：${cut(l[PlanLogs.body], 200)}")
        }
        return out.result("")
    }

    // ── 灵感、档案、决定、心情 ──

    private fun ideas(a: Args): String {
        val tag = a.str("tag")?.removePrefix("#")
        val keyword = a.str("keyword")
        val terms = keyword?.let { RoomContext.terms(it).ifEmpty { setOf(it.lowercase()) } }
        val limit = (a.int("limit") ?: 30).coerceIn(1, 60)
        val out = Out(book)
        val matched = Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.orderBy(Ideas.createdAt, SortOrder.DESC)
            .filter { r -> (tag == null || Tags.has(r[Ideas.body], tag)) && (terms == null || RoomContext.score(r[Ideas.body], terms) > 0) }
        matched.take(limit).forEach { r -> out.item(EntityType.Idea, r[Ideas.id], cut(r[Ideas.body], 80), r[Ideas.createdAt], "灵感（${day(dateOf(r[Ideas.createdAt]))}，${who(r[Ideas.authorId])}）· ${cut(r[Ideas.body], 300)}") }
        out.more((matched.size - limit).coerceAtLeast(0))
        return out.result("没有符合条件的灵感")
    }

    private fun archive(a: Args): String {
        val kind = a.str("kind")?.lowercase()
        val out = Out(book)
        ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }.orderBy(ArchiveItems.updatedAt, SortOrder.DESC)
            .filter { kind == null || it[ArchiveItems.kind] == kind }
            .forEach { r -> out.item(EntityType.ArchiveItem, r[ArchiveItems.id], cut(r[ArchiveItems.title], 80), r[ArchiveItems.updatedAt], archiveLine(r)) }
        return out.result("档案里还没有这类内容")
    }

    private fun decisions(a: Args): String {
        val id = ref(a, EntityType.Decision)
        val out = Out(book)
        if (id == null) {
            val status = a.choice("status", listOf("all", "decided", "open"), "all")
            Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }.orderBy(Decisions.updatedAt, SortOrder.DESC)
                .filter { status == "all" || (it[Decisions.finalChoice] != null) == (status == "decided") }
                .forEach { r -> out.item(EntityType.Decision, r[Decisions.id], cut(r[Decisions.question], 80), r[Decisions.updatedAt], decisionLine(r)) }
            return out.result("没有符合条件的决定")
        }
        val r = Decisions.selectAll().where { (Decisions.id eq id) and (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }.singleOrNull() ?: return "这个决定已经删掉了"
        out.item(EntityType.Decision, id, cut(r[Decisions.question], 80), r[Decisions.updatedAt], decisionLine(r) + "（${who(r[Decisions.createdBy])}提出，${day(dateOf(r[Decisions.createdAt]))}）")
        if (r[Decisions.optionList].isNotEmpty()) out.text("备选：" + r[Decisions.optionList].joinToString("／"))
        r[Decisions.concerns].forEach { c -> out.text("${who(c.userId)}在意：${cut(c.text, 200)}") }
        return out.result("")
    }

    private fun moods(a: Args): String {
        val (from, to) = range(a, 92)
        val author = person(a)
        val rows = Moods.selectAll().where {
            (Moods.roomId eq roomId) and Moods.deletedAt.isNull() and (Moods.createdAt greaterEq startOf(from)) and (Moods.createdAt less startOf(to.plusDays(1))) and
                (if (author != null) Moods.authorId eq author else Op.TRUE)
        }.orderBy(Moods.createdAt, SortOrder.DESC).toList()
        val replies = if (rows.isEmpty()) {
            emptyMap()
        } else {
            MoodResponses.selectAll().where { (MoodResponses.roomId eq roomId) and MoodResponses.deletedAt.isNull() and (MoodResponses.moodId inList rows.map { it[Moods.id] }) }
                .groupBy({ it[MoodResponses.moodId] }, { "${who(it[MoodResponses.authorId])}「${replyWord(it[MoodResponses.kind])}」" })
        }
        val out = Out(book)
        rows.forEach { r -> out.item(EntityType.Mood, r[Moods.id], cut(MoodWords.line(r[Moods.label], r[Moods.intensity], r[Moods.note]), 80), r[Moods.createdAt], moodLine(r, replies[r[Moods.id]].orEmpty())) }
        return out.result("${day(from)}到${day(to)}没有心情记录")
    }

    // ── 问答 ──

    private class QnaRow(val id: UUID, val at: Instant, val question: String, val answers: List<String>, val line: String)

    /** 问答的每一轮；回答只在揭晓后给。[range] 为空时是全部。 */
    private fun qnaRows(range: Pair<LocalDate, LocalDate>?): List<QnaRow> {
        val rounds = QnaRounds.selectAll().where {
            (QnaRounds.roomId eq roomId) and QnaRounds.deletedAt.isNull() and
                (if (range != null) (QnaRounds.roundDate greaterEq range.first) and (QnaRounds.roundDate lessEq range.second) else Op.TRUE)
        }.orderBy(QnaRounds.roundDate, SortOrder.DESC).toList()
        if (rounds.isEmpty()) return emptyList()
        val questions = Questions.selectAll().where { (Questions.roomId eq roomId) and (Questions.id inList rounds.map { it[QnaRounds.questionId] }) }.associate { it[Questions.id] to it[Questions.text] }
        val revealed = rounds.filter { it[QnaRounds.revealedAt] != null }.map { it[QnaRounds.id] }
        val answers = if (revealed.isEmpty()) {
            emptyMap()
        } else {
            Answers.selectAll().where { (Answers.roomId eq roomId) and Answers.deletedAt.isNull() and (Answers.roundId inList revealed) and Answers.confirmedAt.isNotNull() }
                .groupBy({ it[Answers.roundId] }, { "${who(it[Answers.authorId])}：${cut(it[Answers.body], 300)}" })
        }
        return rounds.map { r ->
            val q = questions[r[QnaRounds.questionId]] ?: "（题目已删除）"
            val a = if (r[QnaRounds.revealedAt] != null) answers[r[QnaRounds.id]].orEmpty() else emptyList()
            val tail = if (r[QnaRounds.revealedAt] != null) (if (a.isEmpty()) "（没有回答）" else "；" + a.joinToString("；")) else "（还没揭晓，回答看不到）"
            QnaRow(r[QnaRounds.id], startOf(r[QnaRounds.roundDate]), q, a, "问答（${day(r[QnaRounds.roundDate])}）· $q$tail")
        }
    }

    private fun qna(a: Args): String {
        val from = a.date("from") ?: today.minusDays(29)
        val to = a.date("to") ?: today
        if (to.isBefore(from)) throw BadArgs("to 不能早于 from")
        if (ChronoUnit.DAYS.between(from, to) > 366) throw BadArgs("一次最多查 366 天，请分几次查")
        val out = Out(book)
        qnaRows(from to to).forEach { row -> out.item(EntityType.QnaRound, row.id, cut(row.question, 80), row.at, row.line) }
        return out.result("${day(from)}到${day(to)}没有问答")
    }

    // ── 写作、留言板 ──

    /** 每篇没删的文稿和它最新一版的正文（只有保存过的版本，没保存的草稿不在服务端）。 */
    private fun latestBodies(): List<Pair<ResultRow, String>> {
        val docs = Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() and (Documents.latestVersion greater 0) }.toList()
        if (docs.isEmpty()) return emptyList()
        val bodies = DocumentVersions.select(DocumentVersions.documentId, DocumentVersions.version, DocumentVersions.body)
            .where { DocumentVersions.documentId inList docs.map { it[Documents.id] } }
            .toList()
        return docs.mapNotNull { d ->
            bodies.firstOrNull { it[DocumentVersions.documentId] == d[Documents.id] && it[DocumentVersions.version] == d[Documents.latestVersion] }?.let { d to it[DocumentVersions.body] }
        }
    }

    private fun documents(a: Args): String {
        val id = ref(a, EntityType.Document)
        val out = Out(book)
        if (id == null) {
            val category = a.str("category")
            Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.orderBy(Documents.updatedAt, SortOrder.DESC)
                .filter { category == null || it[Documents.category] == category }
                .forEach { d ->
                    val line = "文稿《${d[Documents.title].ifBlank { "没有标题" }}》" + (d[Documents.category]?.let { " · $it" } ?: "") +
                        (if (d[Documents.latestVersion] > 0) " · 第 ${d[Documents.latestVersion]} 版，${d[Documents.charCount]} 字，${who(d[Documents.latestAuthorId])}${day(dateOf(d[Documents.updatedAt]))}保存" else " · 还没保存过") +
                        (if (d[Documents.pinned]) " · 置顶" else "")
                    out.item(EntityType.Document, d[Documents.id], cut(d[Documents.title], 80), d[Documents.updatedAt], line)
                }
            return out.result("还没有文稿")
        }
        val d = Documents.selectAll().where { (Documents.id eq id) and (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.singleOrNull() ?: return "这篇文稿已经删掉了"
        val body = DocumentVersions.select(DocumentVersions.body).where { (DocumentVersions.documentId eq id) and (DocumentVersions.version eq d[Documents.latestVersion]) }
            .singleOrNull()?.get(DocumentVersions.body) ?: return "《${d[Documents.title]}》还没保存过，没有正文"
        val offset = (a.int("offset") ?: 0).coerceIn(0, body.length)
        val end = (offset + DOC_CHUNK).coerceAtMost(body.length)
        out.item(EntityType.Document, id, cut(d[Documents.title], 80), d[Documents.updatedAt], "文稿《${d[Documents.title]}》第 ${d[Documents.latestVersion]} 版，共 ${body.length} 字：")
        out.text(body.substring(offset, end))
        if (end < body.length) out.text("（读到第 $end 字，还有 ${body.length - end} 字：再调一次并传 offset=$end 接着读）")
        DocComments.selectAll().where { (DocComments.documentId eq id) and (DocComments.roomId eq roomId) and DocComments.deletedAt.isNull() }.orderBy(DocComments.createdAt).forEach { c ->
            out.text(
                "留言（${who(c[DocComments.authorId])}，${day(dateOf(c[DocComments.createdAt]))}" + (if (c[DocComments.resolvedAt] != null) "，已解决" else "") + "）" +
                    (c[DocComments.quote]?.let { "针对「${cut(it, 60)}」" } ?: "") + "：${cut(c[DocComments.body], 200)}",
            )
        }
        return out.result("")
    }

    private fun board(a: Args): String {
        val id = ref(a, EntityType.BoardTopic)
        val out = Out(book)
        if (id == null) {
            val counts = BoardPosts.select(BoardPosts.topicId).where { (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() }.groupingBy { it[BoardPosts.topicId] }.eachCount()
            BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.orderBy(BoardTopics.updatedAt, SortOrder.DESC).forEach { t ->
                out.item(
                    EntityType.BoardTopic, t[BoardTopics.id], cut(t[BoardTopics.title], 80), t[BoardTopics.createdAt],
                    "留言板话题《${t[BoardTopics.title]}》（${who(t[BoardTopics.authorId])}，${day(dateOf(t[BoardTopics.createdAt]))}）· ${counts[t[BoardTopics.id]] ?: 0} 条" + (if (t[BoardTopics.pinnedAt] != null) " · 置顶" else ""),
                )
            }
            return out.result("留言板还是空的")
        }
        val t = BoardTopics.selectAll().where { (BoardTopics.id eq id) and (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.singleOrNull() ?: return "这个话题已经删掉了"
        out.item(EntityType.BoardTopic, id, cut(t[BoardTopics.title], 80), t[BoardTopics.createdAt], "留言板话题《${t[BoardTopics.title]}》：")
        BoardPosts.selectAll().where { (BoardPosts.topicId eq id) and (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() }.orderBy(BoardPosts.createdAt).forEach { p ->
            out.text("${who(p[BoardPosts.authorId])}（${day(dateOf(p[BoardPosts.createdAt]))}）" + (p[BoardPosts.quoteExcerpt]?.let { "回复「${cut(it, 40)}」" } ?: "") + "：${cut(p[BoardPosts.body], 500)}")
        }
        return out.result("")
    }

    // ── 阅读、审稿、总结 ──

    /** 公开的划线、摘录（书签和 AI 解释不算；没公开的是个人笔记，永远不给）。 */
    private fun sharedHighlights(bookId: UUID?): List<ResultRow> =
        Highlights.selectAll().where {
            (Highlights.roomId eq roomId) and Highlights.deletedAt.isNull() and (Highlights.shared eq true) and
                (Highlights.kind inList listOf("highlight", "excerpt")) and (if (bookId != null) Highlights.bookId eq bookId else Op.TRUE)
        }.orderBy(Highlights.createdAt).toList()

    private fun highlightLine(h: ResultRow) =
        "${who(h[Highlights.userId])}的${if (h[Highlights.kind] == "excerpt") "摘录" else "划线"}：「${cut(h[Highlights.text], 200)}」" + (h[Highlights.note]?.let { " 感想：${cut(it, 200)}" } ?: "")

    private fun reading(a: Args): String {
        val id = ref(a, EntityType.Book)
        val out = Out(book)
        val progress = ReadingProgressTable.selectAll().where { (ReadingProgressTable.roomId eq roomId) and ReadingProgressTable.deletedAt.isNull() }
            .groupBy({ it[ReadingProgressTable.bookId] }, { "${who(it[ReadingProgressTable.userId])}读到 ${(it[ReadingProgressTable.progress] * 100).toInt()}%" })
        fun line(b: ResultRow) = "书《${b[Books.title]}》" + (b[Books.author]?.let { "（$it）" } ?: "") + "，${who(b[Books.addedBy])}加的" +
            (progress[b[Books.id]]?.let { " · " + it.joinToString("，") } ?: " · 还没人开始读") +
            (b[Books.planTargetDate]?.let { " · 计划 ${day(it)}读完" } ?: "") + (b[Books.planNote]?.let { " · $it" } ?: "")
        if (id == null) {
            Books.selectAll().where { (Books.roomId eq roomId) and Books.deletedAt.isNull() }.orderBy(Books.createdAt, SortOrder.DESC).forEach { b ->
                out.item(EntityType.Book, b[Books.id], cut(b[Books.title], 80), b[Books.createdAt], line(b))
            }
            return out.result("书架上还没有书")
        }
        val b = Books.selectAll().where { (Books.id eq id) and (Books.roomId eq roomId) and Books.deletedAt.isNull() }.singleOrNull() ?: return "这本书已经删掉了"
        out.item(EntityType.Book, id, cut(b[Books.title], 80), b[Books.createdAt], line(b))
        sharedHighlights(id).forEach { h -> out.text(highlightLine(h)) }
        return out.result("")
    }

    private fun review(a: Args): String {
        val id = ref(a, EntityType.ReviewDocument)
        val out = Out(book)
        if (id == null) {
            val open = Annotations.select(Annotations.documentId).where { (Annotations.roomId eq roomId) and Annotations.deletedAt.isNull() and (Annotations.status eq "open") }
                .groupingBy { it[Annotations.documentId] }.eachCount()
            ReviewDocuments.selectAll().where { (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }.orderBy(ReviewDocuments.updatedAt, SortOrder.DESC).forEach { d ->
                out.item(
                    EntityType.ReviewDocument, d[ReviewDocuments.id], cut(d[ReviewDocuments.title], 80), d[ReviewDocuments.updatedAt],
                    "审稿文件《${d[ReviewDocuments.title]}》· 第 ${d[ReviewDocuments.latestVersion]} 版 · ${who(d[ReviewDocuments.createdBy])}上传 · ${open[d[ReviewDocuments.id]] ?: 0} 条待处理",
                )
            }
            return out.result("还没有审稿文件")
        }
        val d = ReviewDocuments.selectAll().where { (ReviewDocuments.id eq id) and (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }.singleOrNull() ?: return "这份文件已经删掉了"
        out.item(EntityType.ReviewDocument, id, cut(d[ReviewDocuments.title], 80), d[ReviewDocuments.updatedAt], "审稿文件《${d[ReviewDocuments.title]}》，第 ${d[ReviewDocuments.latestVersion]} 版：")
        val notes = Annotations.selectAll().where { (Annotations.documentId eq id) and (Annotations.roomId eq roomId) and Annotations.deletedAt.isNull() }.orderBy(Annotations.createdAt).toList()
        val replies = if (notes.isEmpty()) {
            emptyMap()
        } else {
            AnnotationReplies.selectAll().where { (AnnotationReplies.roomId eq roomId) and AnnotationReplies.deletedAt.isNull() and (AnnotationReplies.annotationId inList notes.map { it[Annotations.id] }) }
                .orderBy(AnnotationReplies.createdAt).groupBy({ it[AnnotationReplies.annotationId] }, { "${who(it[AnnotationReplies.authorId])}：${cut(it[AnnotationReplies.body], 150)}" })
        }
        notes.forEach { n ->
            val status = when (n[Annotations.status]) { "accepted" -> "已接受"; "archived" -> "已归档"; else -> "待处理" }
            val kind = if (n[Annotations.kind] == "proposal") "修改提议" else "意见"
            out.text("$kind（${who(n[Annotations.authorId])}，$status）：${cut(n[Annotations.body], 200)}" + replies[n[Annotations.id]].orEmpty().joinToString("") { " ／ 回复 $it" })
        }
        AiFindings.selectAll().where { (AiFindings.documentId eq id) and (AiFindings.roomId eq roomId) and AiFindings.deletedAt.isNull() and (AiFindings.status neq "dismissed") }
            .orderBy(AiFindings.createdAt).forEach { f -> out.text("AI 审稿发现：${f[AiFindings.title]}：${cut(f[AiFindings.body], 150)}") }
        return out.result("")
    }

    private fun summaryTitle(r: ResultRow): String {
        val s = r[Summaries.rangeStart]
        val e = r[Summaries.rangeEnd]
        return when (r[Summaries.kind]) {
            "year" -> "${s.year} 年度回顾"
            "month" -> "${s.year}年${s.monthValue}月"
            "week" -> "${md(s)}–${md(e)} 这一周"
            else -> "${md(s)}–${md(e)}"
        }
    }

    private fun stripCitations(text: String) = text.replace(Regex("\\[\\d{1,4}]"), "")

    private fun summaries(a: Args): String {
        val id = ref(a, EntityType.Summary)
        val out = Out(book)
        if (id == null) {
            Summaries.selectAll().where { (Summaries.roomId eq roomId) and Summaries.deletedAt.isNull() }.orderBy(Summaries.rangeStart, SortOrder.DESC).forEach { r ->
                out.item(EntityType.Summary, r[Summaries.id], summaryTitle(r), r[Summaries.createdAt], "总结《${summaryTitle(r)}》· ${cut(stripCitations(r[Summaries.body]), 80)}")
            }
            return out.result("还没有总结")
        }
        val r = Summaries.selectAll().where { (Summaries.id eq id) and (Summaries.roomId eq roomId) and Summaries.deletedAt.isNull() }.singleOrNull() ?: return "这份总结已经删掉了"
        out.item(EntityType.Summary, id, summaryTitle(r), r[Summaries.createdAt], "总结《${summaryTitle(r)}》：")
        out.text(cut(stripCitations(r[Summaries.body]), Out.RESULT_MAX - 200))
        return out.result("")
    }

    companion object {
        /** 一行里正文最多多少字 */
        private const val LINE_MAX = 400

        /** 搜索最多列多少条 */
        private const val SEARCH_MAX = 20

        /** 搜聊天时最多扫多少条（最近的在前） */
        private const val SEARCH_SCAN = 300

        /** 读聊天一次最多多少条 */
        const val CHAT_MAX = 80

        /** 文稿一次读多少字 */
        const val DOC_CHUNK = 3000

        private val typeNames = mapOf(
            "message" to "聊天消息", "event" to "日程", "todo" to "待办", "plan" to "计划", "idea" to "灵感", "archive_item" to "档案",
            "decision" to "决定", "mood" to "心情", "qna_round" to "问答", "document" to "文稿", "board_topic" to "留言板话题",
            "book" to "书", "review_document" to "审稿文件", "summary" to "总结",
        )

        private fun typeName(wire: String) = typeNames[wire] ?: wire
    }
}
