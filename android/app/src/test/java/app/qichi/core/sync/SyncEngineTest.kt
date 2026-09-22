package app.qichi.core.sync

import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncFixtures.t0
import app.qichi.core.sync.SyncFixtures.todo
import app.qichi.shared.api.Answer
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.Change
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Member
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.Room
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.QuestionSource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncEngineTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var server: FakeServer
    private lateinit var engine: SyncEngine

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val room = Room(roomId, "两个人的屋檐", null, null, null, "Asia/Shanghai", me, 1, t0, t0)
    private fun member(user: UUID, seq: Long) = Member(
        UUID.randomUUID(), roomId, seq, t0, t0, null, null, user,
        if (user == me) MemberRole.Owner else MemberRole.Member, user.toString().takeLast(4), "名字", null, t0,
    )

    /** 服务端要返回的 sync 页（依次返回）。 */
    private val syncPages = ArrayDeque<SyncResponse>()
    private var bootstrap: Bootstrap? = null

    @Before
    fun setUp() {
        db = SyncFixtures.database()
        store = LocalStore(db)
        server = FakeServer()
        server.custom = { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/bootstrap") ->
                    respond(QichiJson.encodeToString(Bootstrap.serializer(), bootstrap!!), HttpStatusCode.OK, json)
                path.endsWith("/sync") -> {
                    val since = request.url.parameters["since"]!!.toLong()
                    val page = syncPages.removeFirstOrNull() ?: SyncResponse(since, since, false, emptyList())
                    assertEquals(since, page.fromSeq, "客户端应从上一页的 toSeq 继续")
                    respond(QichiJson.encodeToString(SyncResponse.serializer(), page), HttpStatusCode.OK, json)
                }
                else -> null
            }
        }
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store)
    }

    @After
    fun tearDown() = db.close()

    private fun change(entity: Todo, op: ChangeOp = ChangeOp.Upsert) =
        Change(entity.seq, EntityType.Todo, entity.id, op, if (op == ChangeOp.Upsert) EntityCodec.encode(EntityType.Todo, entity) else null)

    private fun boot(lastSeq: Long, todos: List<Todo> = emptyList()) = Bootstrap(
        room = room, members = listOf(member(me, 2), member(partner, 3)), lastSeq = lastSeq, readMarker = null,
        moods = emptyList(), moodReplies = emptyList(), todos = todos, events = emptyList(), messages = emptyList(), hasMoreMessages = false,
    )

    @Test
    fun `首次快照保存问答实体`() = runTest {
        val question = Question(UUID.randomUUID(), roomId, 4, t0, t0, null, null,
            "想一起做什么？", QuestionSource.User, me, null, me, t0)
        val round = QnaRound(UUID.randomUUID(), roomId, 5, t0, t0, null, null,
            question.id, LocalDate.of(2026, 9, 22), null, emptyList())
        val answer = Answer(UUID.randomUUID(), roomId, 6, t0, t0, null, null,
            round.id, me, "去散步", null)
        bootstrap = boot(6).copy(questions = listOf(question), qnaRounds = listOf(round), answers = listOf(answer))
        engine.pull(roomId)
        assertEquals(question, store.get<Question>(EntityType.Question, question.id)?.value)
        assertEquals(round, store.get<QnaRound>(EntityType.QnaRound, round.id)?.value)
        assertEquals(answer, store.get<Answer>(EntityType.Answer, answer.id)?.value)
    }

    @Test
    fun `第一次拉取走 bootstrap，之后按 lastSeq 增量，分页拉到没有为止`() = runTest {
        val a = todo("买菜", seq = 4)
        bootstrap = boot(lastSeq = 4, todos = listOf(a))
        engine.pull(roomId)
        assertEquals(4, engine.lastSeq(roomId))
        assertEquals("买菜", store.get<Todo>(EntityType.Todo, a.id)!!.value.title)
        assertEquals(3, db.entities().listByType(roomId.toString(), "member").size + 1)

        val b = todo("买花", seq = 5)
        val a2 = a.copy(title = "买菜和水果", seq = 6)
        syncPages += SyncResponse(4, 5, true, listOf(change(b)))
        syncPages += SyncResponse(5, 6, false, listOf(change(a2)))
        engine.pull(roomId)
        assertEquals(6, engine.lastSeq(roomId))
        assertEquals("买菜和水果", store.get<Todo>(EntityType.Todo, a.id)!!.value.title)
        assertEquals(SyncState.SYNCED, store.get<Todo>(EntityType.Todo, b.id)!!.syncState)
    }

    @Test
    fun `拉取不会覆盖本机还没发出的修改，但会记下服务端的最新内容`() = runTest {
        val a = todo("原标题", seq = 4)
        bootstrap = boot(lastSeq = 4, todos = listOf(a))
        engine.pull(roomId)

        store.writeLocal(roomId, a.copy(title = "我改的"), OutboxOp.patch("rooms/$roomId/todos/${a.id}", UpdateTodoRequest(title = Patch.of("我改的"))))
        syncPages += SyncResponse(4, 5, false, listOf(change(a.copy(note = "对方加的备注", seq = 5))))
        engine.pull(roomId)

        val row = db.entities().get("todo", a.id.toString())!!
        assertEquals(SyncState.PENDING, row.syncState)
        assertTrue(row.json.contains("我改的"))
        assertTrue(row.serverJson!!.contains("对方加的备注"))
    }

    @Test
    fun `彻底删除的实体从本机删除`() = runTest {
        val a = todo("临时", seq = 4)
        bootstrap = boot(lastSeq = 4, todos = listOf(a))
        engine.pull(roomId)
        syncPages += SyncResponse(4, 7, false, listOf(change(a.copy(seq = 7), ChangeOp.Delete)))
        engine.pull(roomId)
        assertNull(store.get<Todo>(EntityType.Todo, a.id))
    }

    @Test
    fun `服务端 seq 不比本地新时不拉取`() = runTest {
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        val before = server.requests.size
        engine.pullIfBehind(roomId, 4)
        assertEquals(before, server.requests.size)
        engine.pullIfBehind(roomId, 5)
        assertEquals(before + 1, server.requests.size)
    }
}
