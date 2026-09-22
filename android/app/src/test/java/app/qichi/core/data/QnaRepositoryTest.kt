package app.qichi.core.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.Answer
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.QuestionSource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QnaRepositoryTest {
    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var server: FakeServer
    private lateinit var qna: QnaRepository

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        server = FakeServer()
        val api = SyncFixtures.api(server.engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        qna = QnaRepository(db, store, api, SyncEngine(api, db, store), SyncScheduler(context), session)
    }

    @After fun tearDown() = db.close()

    @Test fun `离线出题与回答确认按顺序写进发件箱`() = runTest {
        val question = qna.createQuestion(roomId, "  你喜欢哪个周末？  ")
        assertEquals("你喜欢哪个周末？", question.text)
        assertEquals(QuestionSource.User, store.get<Question>(EntityType.Question, question.id)?.value?.source)
        assertTrue(store.get<Question>(EntityType.Question, question.id)!!.isPending)

        val round = QnaRound(UUID.randomUUID(), roomId, 1, question.createdAt, question.createdAt,
            null, null, question.id, LocalDate.of(2026, 9, 22), null, emptyList())
        store.applyServer(round)
        val answer = qna.writeAnswer(round, null, "  一起散步  ")
        qna.confirm(round)
        assertEquals("一起散步", store.get<Answer>(EntityType.Answer, answer.id)?.value?.body)
        assertTrue(store.get<Answer>(EntityType.Answer, answer.id)!!.isPending)
        assertEquals(listOf(me), store.get<QnaRound>(EntityType.QnaRound, round.id)?.value?.confirmedBy)
        val ops = db.outbox().all()
        assertEquals(listOf("rooms/$roomId/questions", "rooms/$roomId/qna/rounds/${round.id}/answer",
            "rooms/$roomId/qna/rounds/${round.id}/confirm"), ops.map { it.path })
        assertEquals(answer.id, QichiJson.decodeFromString(WriteAnswerRequest.serializer(), ops[1].bodyJson!!).id)
        qna.delete(question)
        assertEquals(me, store.get<Question>(EntityType.Question, question.id)?.value?.deletedBy)
        assertEquals("rooms/$roomId/questions/${question.id}", db.outbox().all().last().path)
    }

    @Test fun `AI 出题只发在线请求，不写发件箱`() = runTest {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        server.custom = { request ->
            if (request.url.encodedPath.endsWith("/ai/question-suggest")) {
                respond(QichiJson.encodeToString(AiJobAccepted.serializer(), AiJobAccepted(UUID.randomUUID(), AiJobStatus.Queued)),
                    HttpStatusCode.Accepted, json)
            } else null
        }
        assertEquals(AiJobStatus.Queued, qna.suggest(roomId).status)
        assertTrue(db.outbox().all().isEmpty())
    }
}
