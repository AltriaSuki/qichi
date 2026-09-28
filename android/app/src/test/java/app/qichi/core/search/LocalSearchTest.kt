package app.qichi.core.search

import app.qichi.shared.api.Answer
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Book
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Message
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.Todo
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.QuestionSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSearchTest {
    private val room = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    private val t0 = Instant.parse("2026-09-01T10:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private fun id() = UUID.randomUUID()

    private fun message(body: String, minutes: Long = 0, retracted: Boolean = false, deleted: Boolean = false, kind: MessageKind = MessageKind.Text) = Message(
        id(), room, 1, at(minutes), at(minutes), if (deleted) at(minutes) else null, null, partner, kind, body, null,
        null, null, null, if (retracted) at(minutes) else null, if (retracted) partner else null, 1,
    )

    private fun todo(title: String, deleted: Boolean = false) = Todo(
        id(), room, 1, t0, t0, if (deleted) t0 else null, null, title, null, me, null, null, null, null, null, null, null, null,
    )

    private fun idea(body: String) = Idea(id(), room, 1, t0, t0, null, null, me, body)

    @Test
    fun `各类都搜得到，按类分组、每组最新的在前`() {
        val plan = Plan(id(), room, 1, t0, t0, null, null, "去海边", me, PlanStatus.Active, null, null, null, null, null, null)
        val topic = BoardTopic(id(), room, 1, t0, t0, null, null, "周末", me, null)
        val post = BoardPost(id(), room, 1, at(5), at(5), null, null, topic.id, partner, "海边那家民宿不错", null, null, null, 1, null)
        val book = Book(id(), room, 1, t0, t0, null, null, "海边的卡夫卡", "村上春树", id(), 1, me, null, null)
        val corpus = SearchCorpus(
            messages = listOf(message("上次说的海边那家店", 1), message("海边的日落", 9), message("别的")),
            todos = listOf(todo("订海边的票")),
            ideas = listOf(idea("在海边露营")),
            plans = listOf(plan),
            planLogs = listOf(PlanLog(id(), room, 1, at(2), at(2), null, null, plan.id, me, "看好了海边的房子")),
            boardTopics = listOf(topic),
            boardPosts = listOf(post),
            books = listOf(book),
        )

        val groups = searchLocal(corpus, "  海边 ", me)
        assertEquals(listOf("message", "todo", "plan", "idea", "board_topic", "book"), groups.map { it.type })
        val chat = groups.first { it.type == "message" }.hits
        assertEquals(listOf("海边的日落", "上次说的海边那家店"), chat.map { it.snippet })
        assertEquals(plan.id, groups.first { it.type == "plan" }.hits.first().source.id, "进展记录算到计划下")
        val board = groups.first { it.type == "board_topic" }.hits.single()
        assertEquals(topic.id, board.source.id)
        assertEquals(post.id, board.focusId, "打开主题后滚到那一条留言")
    }

    @Test
    fun `几个词都要出现，不分大小写；空的什么都不搜`() {
        val corpus = SearchCorpus(messages = listOf(message("周六去 IKEA 买书架"), message("周六去看电影")))
        assertEquals(1, searchLocal(corpus, "ikea 周六", me).single().hits.size)
        assertTrue(searchLocal(corpus, "   ", me).isEmpty())
    }

    @Test
    fun `撤回的、删除的、系统消息搜不到`() {
        val corpus = SearchCorpus(
            messages = listOf(
                message("秘密计划", retracted = true),
                message("秘密计划", deleted = true),
                message("秘密计划 已加入房间", kind = MessageKind.System),
            ),
            todos = listOf(todo("秘密计划", deleted = true)),
        )
        assertTrue(searchLocal(corpus, "秘密", me).isEmpty())
    }

    @Test
    fun `没揭晓的问答回答搜不到，问题本身搜得到；揭晓后回答也搜得到`() {
        val question = Question(id(), room, 1, t0, t0, null, null, "最想去的城市", QuestionSource.Preset, null, null, null, null)
        val hidden = QnaRound(id(), room, 1, t0, t0, null, null, question.id, LocalDate.of(2026, 9, 1), null, emptyList())
        val revealed = hidden.copy(id = id(), roundDate = LocalDate.of(2026, 9, 2), revealedAt = at(60))
        val corpus = SearchCorpus(
            questions = listOf(question),
            rounds = listOf(hidden, revealed),
            answers = listOf(
                Answer(id(), room, 1, t0, t0, null, null, hidden.id, partner, "京都", null),
                Answer(id(), room, 1, t0, t0, null, null, hidden.id, me, "京都也行", null),
                Answer(id(), room, 1, at(30), at(30), null, null, revealed.id, partner, "里斯本", null),
            ),
        )
        assertTrue(searchLocal(corpus, "京都", me).isEmpty())
        assertEquals(revealed.id, searchLocal(corpus, "里斯本", me).single().hits.single().source.id)
        assertEquals(2, searchLocal(corpus, "城市", me).single().hits.size)
    }

    @Test
    fun `阅读摘录只搜公开的和我自己的`() {
        val book = Book(id(), room, 1, t0, t0, null, null, "某本书", null, id(), 1, me, null, null)
        fun hl(user: UUID, shared: Boolean) = Highlight(id(), room, 1, t0, t0, null, null, book.id, user, HighlightKind.Highlight, "{}", "灯塔在远处", null, shared)
        val corpus = SearchCorpus(books = listOf(book), highlights = listOf(hl(partner, shared = false), hl(me, shared = false)))
        assertEquals(1, searchLocal(corpus, "灯塔", me).single().hits.size)
        val shared = corpus.copy(highlights = corpus.highlights + hl(partner, shared = true))
        assertEquals(2, searchLocal(shared, "灯塔", me).single().hits.size)
    }

    @Test
    fun `摘录取第一个词附近一段，前后超出的用省略号`() {
        val long = "开头".repeat(30) + "关键" + "结尾".repeat(30)
        val s = snippet(long, listOf("关键"))
        assertTrue(s.startsWith("…") && s.endsWith("…") && s.contains("关键"))
        assertEquals("短句 换行", snippet("短句\n换行", listOf("短")))
    }
}
