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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** 服务端比 App 新时的原始数据（里面有 App 不认识的东西）：有就先用它们 */
    private val rawSyncPages = ArrayDeque<JsonObject>()
    private var rawBootstrap: JsonObject? = null
    private var bootstrapCalls = 0

    @Before
    fun setUp() {
        db = SyncFixtures.database()
        store = LocalStore(db)
        server = FakeServer()
        server.custom = { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/bootstrap") -> {
                    bootstrapCalls++
                    respond(rawBootstrap?.toString() ?: QichiJson.encodeToString(Bootstrap.serializer(), bootstrap!!), HttpStatusCode.OK, json)
                }
                path.endsWith("/sync") -> {
                    val since = request.url.parameters["since"]!!.toLong()
                    val raw = rawSyncPages.removeFirstOrNull()
                    if (raw != null) {
                        respond(raw.toString(), HttpStatusCode.OK, json)
                    } else {
                        val page = syncPages.removeFirstOrNull() ?: SyncResponse(since, since, false, emptyList())
                        assertEquals(since, page.fromSeq, "客户端应从上一页的 toSeq 继续")
                        respond(QichiJson.encodeToString(SyncResponse.serializer(), page), HttpStatusCode.OK, json)
                    }
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

    private fun rawPage(from: Long, to: Long, vararg changes: JsonElement) = buildJsonObject {
        put("fromSeq", from)
        put("toSeq", to)
        put("hasMore", false)
        put("changes", JsonArray(changes.toList()))
    }

    private fun rawChange(seq: Long, type: String, data: JsonElement, id: UUID = UUID.randomUUID()) = buildJsonObject {
        put("seq", seq)
        put("type", type)
        put("id", id.toString())
        put("op", "upsert")
        put("data", data)
    }

    private val poll = buildJsonObject { put("question", "周末去哪？") }

    @Test
    fun `同步里有不认识的实体类型或枚举取值：认得的照常写入、lastSeq 照常前进、记下需要更新`() = runTest {
        val unknown = UnknownContent(UnknownContent.MemoryStore(), currentVersion = 10)
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store, unknown)
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        assertFalse(unknown.needsNewerApp.value)

        val b = todo("买花", seq = 5)
        val guest = JsonObject(QichiJson.encodeToJsonElement(Member.serializer(), member(partner, 7)).jsonObject + ("role" to JsonPrimitive("guest")))
        rawSyncPages += rawPage(
            4, 8,
            QichiJson.encodeToJsonElement(Change.serializer(), change(b)),
            rawChange(6, "poll", poll),
            rawChange(7, "member", guest),
        )
        engine.pull(roomId)

        assertEquals(8, engine.lastSeq(roomId))
        assertEquals("买花", store.get<Todo>(EntityType.Todo, b.id)!!.value.title)
        assertTrue(unknown.needsNewerApp.value)
        assertFalse(unknown.needsRebootstrap(roomId))
    }

    @Test
    fun `升级到新版后重新快照：补回旧版跳过的内容，记录清掉`() = runTest {
        val marks = UnknownContent.MemoryStore()
        val v10 = UnknownContent(marks, currentVersion = 10)
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store, v10)
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        rawSyncPages += rawPage(4, 5, rawChange(5, "poll", poll))
        engine.pull(roomId)
        assertEquals(5, engine.lastSeq(roomId))
        assertEquals(1, bootstrapCalls)

        // 装了新版（版本号 11）：同一份记录，看得出是旧版留下的
        val v11 = UnknownContent(marks, currentVersion = 11)
        assertTrue(v11.needsRebootstrap(roomId))
        assertFalse(v11.needsNewerApp.value)
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store, v11)
        val c = todo("新版才看得到的", seq = 5)
        bootstrap = boot(lastSeq = 5, todos = listOf(c))
        engine.pull(roomId)

        assertEquals(2, bootstrapCalls)
        assertEquals("新版才看得到的", store.get<Todo>(EntityType.Todo, c.id)!!.value.title)
        assertFalse(v11.needsRebootstrap(roomId))
        assertFalse(v11.needsNewerApp.value)
        // 之后照常增量，不再快照
        engine.pull(roomId)
        assertEquals(2, bootstrapCalls)
    }

    @Test
    fun `快照里认不出来的去掉，认得的照常写入并记下`() = runTest {
        val unknown = UnknownContent(UnknownContent.MemoryStore(), currentVersion = 10)
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store, unknown)
        val a = todo("买菜", seq = 4)
        val base = QichiJson.encodeToJsonElement(Bootstrap.serializer(), boot(lastSeq = 4, todos = listOf(a))).jsonObject
        rawBootstrap = JsonObject(base + ("polls" to JsonArray(listOf(poll))))
        engine.pull(roomId)

        assertEquals(4, engine.lastSeq(roomId))
        assertEquals("买菜", store.get<Todo>(EntityType.Todo, a.id)!!.value.title)
        assertTrue(unknown.needsNewerApp.value)
    }

    @Test
    fun `拉取出错时记下问题，这个房间拉取成功后清掉；断网不算问题`() = runTest {
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        assertNull(engine.problem.value)

        server.failNext500 = 1
        assertTrue(runCatching { engine.pull(roomId) }.isFailure)
        assertEquals(roomId, engine.problem.value?.roomId)

        engine.pull(roomId)
        assertNull(engine.problem.value)

        server.online = false
        assertTrue(runCatching { engine.pull(roomId) }.isFailure)
        assertNull(engine.problem.value)
    }

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

    @Test
    fun `回到前台时两处同时要拉：排在后面的发现已经不落后，就不再发请求（P17-06）`() = runTest {
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        syncPages += SyncResponse(4, 5, false, listOf(change(todo("新的", seq = 5))))
        val before = server.requests.size
        val first = launch { engine.pull(roomId) }
        val second = launch { engine.pullIfBehind(roomId, 5) }
        first.join()
        second.join()
        assertEquals(5, engine.lastSeq(roomId))
        assertEquals(before + 1, server.requests.size)
    }

    @Test
    fun `刚拉过的房间，回到前台「全部拉一遍」时不再发请求（P17-06）`() = runTest {
        var clock = 1_000_000L
        engine = SyncEngine(SyncFixtures.api(server.engine), db, store, now = { clock })
        bootstrap = boot(lastSeq = 4)
        engine.pull(roomId)
        val before = server.requests.size
        clock += 3_000
        engine.pullIfStale(roomId)
        assertEquals(before, server.requests.size)
        clock += SyncEngine.FRESH_MS
        engine.pullIfStale(roomId)
        assertEquals(before + 1, server.requests.size)
    }
}
