package app.qichi.server.qna

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.ai.FakeGateway
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.Answer
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateQuestionRequest
import app.qichi.shared.api.QnaToday
import app.qichi.shared.api.Question
import app.qichi.shared.api.QuestionSuggestRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.QuestionSource
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QnaTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `答案在接口、同步、快照里保密，双方确认后同时揭晓，重复请求幂等`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room"
        val first = aqi.get("$path/qna/today").body<QnaToday>()
        assertEquals(first.round.id, chi.get("$path/qna/today").body<QnaToday>().round.id)
        val aId = UuidV7.generate()
        val bId = UuidV7.generate()
        val answerPath = "$path/qna/rounds/${first.round.id}/answer"
        assertEquals(HttpStatusCode.Created, aqi.put(answerPath, WriteAnswerRequest(aId, "我的秘密")).status)
        assertEquals(HttpStatusCode.OK, aqi.put(answerPath, WriteAnswerRequest(aId, "我的秘密")).status)
        val bView = chi.get("$path/qna/today").body<QnaToday>()
        assertNull(bView.partnerAnswer)
        assertFalse(bView.partnerConfirmed)
        assertTrue(chi.get("$path/bootstrap").body<Bootstrap>().answers.none { it.id == aId })
        assertTrue(chi.get("$path/sync?since=0").body<SyncResponse>().changes.none { it.id == aId })

        aqi.post("$path/qna/rounds/${first.round.id}/confirm")
        assertEquals(HttpStatusCode.OK, aqi.put(answerPath, WriteAnswerRequest(aId, "我的秘密")).status)
        assertTrue(chi.get("$path/qna/today").body<QnaToday>().partnerConfirmed)
        assertNull(chi.get("$path/qna/today").body<QnaToday>().partnerAnswer)
        assertTrue(chi.get("$path/bootstrap").body<Bootstrap>().answers.none { it.id == aId })
        assertTrue(chi.get("$path/sync?since=0").body<SyncResponse>().changes.none { it.id == aId })
        aqi.put(answerPath, WriteAnswerRequest(aId, "改不了")).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictVersion)

        chi.put(answerPath, WriteAnswerRequest(bId, "我也有秘密"))
        val beforeReveal = chi.get("$path/bootstrap").body<Bootstrap>().lastSeq
        chi.post("$path/qna/rounds/${first.round.id}/confirm")
        val revealed = aqi.get("$path/qna/today").body<QnaToday>()
        assertEquals("我也有秘密", revealed.partnerAnswer?.body)
        assertTrue(revealed.round.revealedAt != null)
        assertEquals(setOf(aId, bId), chi.get("$path/bootstrap").body<Bootstrap>().answers.map { it.id }.toSet())
        val changes = chi.get("$path/sync?since=$beforeReveal").body<SyncResponse>().changes
        assertEquals(setOf(aId, bId), changes.filter { it.type == EntityType.Answer }.map { it.id }.toSet())
        val seq = chi.get("$path/bootstrap").body<Bootstrap>().lastSeq
        assertEquals(HttpStatusCode.OK, chi.put(answerPath, WriteAnswerRequest(bId, "我也有秘密")).status)
        chi.post("$path/qna/rounds/${first.round.id}/confirm")
        assertEquals(seq, chi.get("$path/bootstrap").body<Bootstrap>().lastSeq)
    }

    @Test fun `今日按房间时区计算；非成员 404；删除后进入回收站并恢复`() {
        val clock = MutableClock(Instant.parse("2026-09-21T16:30:00Z"))
        serverTest(testContext(clock = clock)) { client ->
            val api = Api(client)
            val (aqi, _, room) = api.pair()
            val outsider = api.outsider(aqi)
            val path = "/api/v1/rooms/$room"
            val first = aqi.get("$path/qna/today").body<QnaToday>()
            assertEquals(LocalDate.of(2026, 9, 22), first.round.roundDate)
            outsider.get("$path/qna/today").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.get("$path/questions").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.get("$path/bootstrap").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.put("$path/qna/rounds/${first.round.id}/answer", WriteAnswerRequest(UuidV7.generate(), "偷看"))
                .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.post("$path/qna/rounds/${first.round.id}/confirm").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            outsider.post("$path/ai/question-suggest", QuestionSuggestRequest(UuidV7.generate()))
                .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            val questionId = UuidV7.generate()
            assertEquals(HttpStatusCode.Created, aqi.post("$path/questions", CreateQuestionRequest(questionId, "我们想去哪里？")).status)
            assertEquals(HttpStatusCode.OK, aqi.post("$path/questions", CreateQuestionRequest(questionId, "我们想去哪里？")).status)
            aqi.delete("$path/questions/$questionId")
            assertTrue(aqi.get("$path/trash").body<TrashPage>().items.any { it.id == questionId })
            aqi.post("$path/trash/question/$questionId/restore")
            assertTrue(aqi.get("$path/questions").body<List<Question>>().any { it.id == questionId })
            clock.advance(Duration.ofDays(1))
            val tomorrow = api.loginOk("aqi")
            assertEquals(LocalDate.of(2026, 9, 23), tomorrow.get("$path/qna/today").body<QnaToday>().round.roundDate)
        }
    }

    @Test fun `AI 出题异步写进待采纳题库`() {
        val gateway = FakeGateway().apply { answer = "一些无关说明\n你想一起再去哪里？\n另一段说明" }
        val ctx = testContext(aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val path = "/api/v1/rooms/$room"
            val jobId = UuidV7.generate()
            assertEquals(HttpStatusCode.Accepted, aqi.post("$path/ai/question-suggest", QuestionSuggestRequest(jobId)).status)
            ctx.jobs.drain()
            val suggested = aqi.get("$path/questions?status=suggested").body<List<Question>>()
            assertEquals(jobId, suggested.single().suggestedByJobId)
            assertEquals("你想一起再去哪里？", suggested.single().text)
            aqi.post("$path/questions/${suggested.single().id}/adopt")
            assertTrue(aqi.get("$path/questions?status=adopted").body<List<Question>>().any { it.id == suggested.single().id })
        }
    }

    @Test fun `先用未问过的采纳题，再用预置题，最后复用最久没问的题`() {
        val clock = MutableClock(Instant.parse("2026-09-21T16:30:00Z"))
        val ctx = testContext(clock = clock)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val user = aqi.userId()
            val custom = ctx.qna.createQuestion(user, room, CreateQuestionRequest(UuidV7.generate(), "我们的第一题？")).first
            assertEquals(custom.id, ctx.qna.today(user, room).question.id)
            repeat(5) {
                clock.advance(Duration.ofDays(1))
                assertEquals(QuestionSource.Preset, ctx.qna.today(user, room).question.source)
            }
            clock.advance(Duration.ofDays(1))
            assertEquals(custom.id, ctx.qna.today(user, room).question.id)
        }
    }

    @Test fun `昨日未揭晓时只返回自己的回答`() {
        val clock = MutableClock(Instant.parse("2026-09-21T16:30:00Z"))
        serverTest(testContext(clock = clock)) { client ->
            val api = Api(client)
            val (aqi, _, room) = api.pair()
            val path = "/api/v1/rooms/$room"
            val round = aqi.get("$path/qna/today").body<QnaToday>().round
            val answerId = UuidV7.generate()
            aqi.put("$path/qna/rounds/${round.id}/answer", WriteAnswerRequest(answerId, "昨天的秘密"))
            aqi.post("$path/qna/rounds/${round.id}/confirm")
            clock.advance(Duration.ofDays(1))
            val owner = api.loginOk("aqi")
            val partner = api.loginOk("xiaochi")
            assertEquals(listOf(answerId), owner.get("$path/qna/today").body<QnaToday>().yesterdayAnswers.map { it.id })
            assertTrue(partner.get("$path/qna/today").body<QnaToday>().yesterdayAnswers.isEmpty())
        }
    }
}
