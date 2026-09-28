package app.qichi.core.search

import app.qichi.shared.api.Answer
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Book
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Document
import app.qichi.shared.api.Event
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Message
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.SummarySource
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MessageKind
import java.time.Instant
import java.util.UUID

// 统一搜索（P16-06）：在本机已同步的数据里找，离线也能用。纯逻辑，不碰数据库和界面。

/** 本机的数据（都不含回收站里的）。 */
data class SearchCorpus(
    val messages: List<Message> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val events: List<Event> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val planLogs: List<PlanLog> = emptyList(),
    val ideas: List<Idea> = emptyList(),
    val archive: List<ArchiveItem> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val documents: List<Document> = emptyList(),
    val boardTopics: List<BoardTopic> = emptyList(),
    val boardPosts: List<BoardPost> = emptyList(),
    val books: List<Book> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val questions: List<Question> = emptyList(),
    val rounds: List<QnaRound> = emptyList(),
    val answers: List<Answer> = emptyList(),
)

/**
 * 一条结果。[source] 用来打开原来那条（和 AI 引用同一套跳转，type 是 sourceKind 认得的类型）；
 * [focusId] 是留言板里具体哪一条留言（打开主题后滚到它），其它为空。
 */
data class SearchHit(val source: SummarySource, val snippet: String, val focusId: UUID? = null)

/** 一类结果，[type] 同 SummarySource.type。 */
data class SearchGroup(val type: String, val hits: List<SearchHit>)

/** 分组的先后：常用的在前。 */
val SEARCH_GROUP_ORDER = listOf(
    "message", "todo", "event", "plan", "idea", "archive_item", "decision", "document", "board_topic", "book", "qna_round",
)

/** 每类最多显示几条（最新的在前）。 */
const val SEARCH_GROUP_LIMIT = 20

/** 搜索词：按空白分开，每个词都要出现（不分大小写）。空的返回空列表。 */
fun searchTerms(query: String): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

internal fun matches(text: String, terms: List<String>): Boolean {
    if (terms.isEmpty()) return false
    val lower = text.lowercase()
    return terms.all { lower.contains(it) }
}

/** 摘录：第一个词附近的一段，前后超出的用「…」。换行压成空格。 */
fun snippet(text: String, terms: List<String>, radius: Int = 24): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val at = terms.firstOrNull()?.let { flat.lowercase().indexOf(it) } ?: -1
    if (at < 0 || flat.length <= radius * 2) return flat.take(radius * 2).let { if (flat.length > it.length) "$it…" else it }
    val start = (at - radius / 2).coerceAtLeast(0)
    val end = (start + radius * 2).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}

/**
 * 在本机数据里搜 [query]，按 [SEARCH_GROUP_ORDER] 分组，每组最新的在前、最多 [SEARCH_GROUP_LIMIT] 条；没有结果的组不出现。
 *
 * - 聊天：文字、照片说明、文件名、AI 回答；撤回的和系统消息不搜。
 * - 计划：标题、下一步、完成说明，里程碑和进展记录算到所属计划下。
 * - 留言板：主题标题和每条留言，留言打开时滚到那一条。
 * - 阅读：书名、作者；摘录只搜公开的和我自己的。
 * - 问答：问题谁都看得到；回答只在那一轮揭晓之后才搜得到（没揭晓的连自己的也不搜，和问答页一样先藏着）。
 */
fun searchLocal(corpus: SearchCorpus, query: String, me: UUID?): List<SearchGroup> {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return emptyList()
    val hits = mutableMapOf<String, MutableList<Pair<Instant, SearchHit>>>()
    fun add(type: String, id: UUID, label: String, at: Instant, text: String, focusId: UUID? = null) {
        hits.getOrPut(type) { mutableListOf() } += at to SearchHit(SummarySource(0, type, id, label, at), snippet(text, terms), focusId)
    }

    corpus.messages.forEach { m ->
        if (m.retractedAt != null || m.deletedAt != null || m.kind == MessageKind.System) return@forEach
        val text = listOfNotNull(m.body.takeIf { it.isNotBlank() }, m.file?.fileName).joinToString(" ")
        if (matches(text, terms)) add("message", m.id, text, m.createdAt, text)
    }
    corpus.todos.filter { it.deletedAt == null }.forEach { t ->
        val text = listOfNotNull(t.title, t.note).joinToString(" ")
        if (matches(text, terms)) add("todo", t.id, t.title, t.createdAt, text)
    }
    corpus.events.filter { it.deletedAt == null }.forEach { e ->
        val text = listOfNotNull(e.title, e.location, e.note).joinToString(" ")
        // 打开时跳到日程那一天，所以时间用开始时间；全天日程取那天中午（UTC），换成手机时区还是同一天
        val at = e.startsAt ?: e.startDate?.atTime(12, 0)?.toInstant(java.time.ZoneOffset.UTC) ?: e.createdAt
        if (matches(text, terms)) add("event", e.id, e.title, at, text)
    }
    val plans = corpus.plans.filter { it.deletedAt == null }.associateBy { it.id }
    plans.values.forEach { p ->
        val text = listOfNotNull(p.title, p.nextStep, p.completionNote).joinToString(" ")
        if (matches(text, terms)) add("plan", p.id, p.title, p.createdAt, text)
    }
    corpus.milestones.filter { it.deletedAt == null }.forEach { m ->
        val plan = plans[m.planId] ?: return@forEach
        if (matches(m.title, terms)) add("plan", plan.id, plan.title, m.createdAt, m.title)
    }
    corpus.planLogs.filter { it.deletedAt == null }.forEach { l ->
        val plan = plans[l.planId] ?: return@forEach
        if (matches(l.body, terms)) add("plan", plan.id, plan.title, l.createdAt, l.body)
    }
    corpus.ideas.filter { it.deletedAt == null }.forEach { i ->
        if (matches(i.body, terms)) add("idea", i.id, i.body, i.createdAt, i.body)
    }
    corpus.archive.filter { it.deletedAt == null }.forEach { a ->
        val text = a.title + " " + a.body
        if (matches(text, terms)) add("archive_item", a.id, a.title, a.createdAt, text)
    }
    corpus.decisions.filter { it.deletedAt == null }.forEach { d ->
        val text = (listOf(d.question) + d.options + listOfNotNull(d.finalChoice)).joinToString(" ")
        if (matches(text, terms)) add("decision", d.id, d.question, d.createdAt, text)
    }
    corpus.documents.filter { it.deletedAt == null }.forEach { d ->
        if (matches(d.title, terms)) add("document", d.id, d.title, d.updatedAt, d.title)
    }
    val topics = corpus.boardTopics.filter { it.deletedAt == null }.associateBy { it.id }
    topics.values.forEach { t ->
        if (matches(t.title, terms)) add("board_topic", t.id, t.title, t.createdAt, t.title)
    }
    corpus.boardPosts.filter { it.deletedAt == null }.forEach { p ->
        val topic = topics[p.topicId] ?: return@forEach
        if (matches(p.body, terms)) add("board_topic", topic.id, topic.title, p.createdAt, p.body, focusId = p.id)
    }
    val books = corpus.books.filter { it.deletedAt == null }.associateBy { it.id }
    books.values.forEach { b ->
        val text = listOfNotNull(b.title, b.author).joinToString(" ")
        if (matches(text, terms)) add("book", b.id, b.title, b.createdAt, text)
    }
    corpus.highlights.filter { it.deletedAt == null && (it.shared || it.userId == me) }.forEach { h ->
        val book = books[h.bookId] ?: return@forEach
        val text = listOfNotNull(h.text, h.note).joinToString(" ")
        if (matches(text, terms)) add("book", book.id, book.title, h.createdAt, text)
    }
    val questions = corpus.questions.associateBy { it.id }
    val rounds = corpus.rounds.filter { it.deletedAt == null }
    val roundsById = rounds.associateBy { it.id }
    rounds.forEach { r ->
        val q = questions[r.questionId] ?: return@forEach
        if (matches(q.text, terms)) add("qna_round", r.id, q.text, r.createdAt, q.text)
    }
    corpus.answers.filter { it.deletedAt == null }.forEach { a ->
        val round = roundsById[a.roundId]?.takeIf { it.revealedAt != null } ?: return@forEach
        val label = questions[round.questionId]?.text ?: return@forEach
        if (matches(a.body, terms)) add("qna_round", round.id, label, a.createdAt, a.body)
    }

    return SEARCH_GROUP_ORDER.mapNotNull { type ->
        val list = hits[type] ?: return@mapNotNull null
        SearchGroup(type, list.sortedByDescending { it.first }.map { it.second }.take(SEARCH_GROUP_LIMIT))
    }
}
