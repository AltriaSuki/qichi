package app.qichi.server.sync

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.db.ChangeNotifier
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Events
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.serverTest
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Event
import app.qichi.shared.api.Patch
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateEventRequest
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 同步完整性（P13-11）：彻底删除时连带的变化都进同步；写入只动本房间；同 id 别人来建是冲突；先读后改不互相覆盖。 */
class SyncIntegrityTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private val writes = EntityWrites(
        RoomWriter(object : ChangeNotifier { override suspend fun roomChanged(roomId: UUID, seq: Long) = Unit }),
        Clock.systemUTC(),
    )

    private suspend fun lastSeq(roomId: UUID): Long = TestDatabase.database.tx { RoomRepository.lastSeq(roomId) }

    @Test
    fun `彻底删除计划：挂在它下面的待办出现在同步里，planId 为空（回收站里的也一样）`() = serverTest { client ->
        val (aqi, xiaochi, room) = Api(client).pair()
        val plan = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/plans", CreatePlanRequest(plan, "搬家", aqi.userId()))
        val todo = UuidV7.generate()
        val trashedTodo = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(todo, "打包书", planId = plan))
        aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(trashedTodo, "退押金", planId = plan))
        aqi.delete("/api/v1/rooms/$room/todos/$trashedTodo")
        aqi.delete("/api/v1/rooms/$room/plans/$plan")
        val since = lastSeq(room)

        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/rooms/$room/trash/plan/$plan").status)

        val changes = xiaochi.get("/api/v1/rooms/$room/sync?since=$since").body<SyncResponse>().changes
        val todos = changes.filter { it.type == EntityType.Todo }.associate { it.id to EntityCodec.decode(EntityType.Todo, it.data!!) as Todo }
        assertEquals(setOf(todo, trashedTodo), todos.keys)
        todos.values.forEach { assertNull(it.planId) }
        assertEquals(ChangeOp.Delete, changes.single { it.type == EntityType.Plan }.op)
    }

    @Test
    fun `只动本房间的行：拿别的房间的 id 来改、来删都是 404，事务整个回滚`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val otherRoom = aqi.createRoom("另一个房间").room.id
        val todo = UuidV7.generate()
        aqi.post("/api/v1/rooms/$otherRoom/todos", CreateTodoRequest(todo, "别的房间的待办"))
        val aqiId = aqi.userId()
        val before = lastSeq(room)

        val update = assertFailsWith<ApiException> {
            TestDatabase.database.tx {
                writes.update(this, room, aqiId, EntityType.Todo, todo, Todos) { it[Todos.title] = "改掉" }
            }
        }
        assertEquals(ProblemCode.NotFound, update.code)
        val delete = assertFailsWith<ApiException> {
            TestDatabase.database.tx { writes.hardDelete(this, room, aqiId, EntityType.Todo, todo, Todos) }
        }
        assertEquals(ProblemCode.NotFound, delete.code)

        assertEquals(before, lastSeq(room), "序号和变更记录都回滚了")
        val title = TestDatabase.database.tx { Todos.select(Todos.title).where { Todos.id eq todo }.single()[Todos.title] }
        assertEquals("别的房间的待办", title)
    }

    @Test
    fun `同一个 id：自己重试原样返回，别人拿来建是 409`() = serverTest { client ->
        val (aqi, xiaochi, room) = Api(client).pair()
        val id = UuidV7.generate()
        val first = aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(id, "买菜"))
        assertEquals(HttpStatusCode.Created, first.status)
        val retry = aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(id, "买菜"))
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(first.body<Todo>(), retry.body<Todo>())

        xiaochi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(id, "买菜")).assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)
        val event = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/events", CreateEventRequest(event, "看电影", allDay = true, startDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 10, 1)))
        xiaochi.post("/api/v1/rooms/$room/events", CreateEventRequest(event, "看电影", allDay = true, startDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 10, 1)))
            .assertProblem(HttpStatusCode.Conflict, ProblemCode.ConflictId)
    }

    /**
     * 让 [change] 在「别人正拿着房间锁」时发出：等它的事务排上队（在等锁）再让别人改 [otherColumn] 并提交，
     * 然后看它写进去的结果。以前先读后锁，它会拿读到的旧值把别人的修改盖回去（Q9）。
     */
    private suspend fun whileSomeoneElseWrites(room: UUID, otherWrite: () -> Unit, change: suspend () -> HttpResponse): HttpResponse =
        coroutineScope {
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val other: Deferred<Unit> = async(Dispatchers.IO) {
                TestDatabase.database.tx {
                    RoomRepository.lockRoom(room)
                    locked.complete(Unit)
                    runBlocking { release.await() }
                    otherWrite()
                }
            }
            locked.await()
            val response = async { change() }
            waitForLockWaiter()
            release.complete(Unit)
            other.await()
            response.await()
        }

    /** 等到有一个数据库连接在排队等锁 */
    private suspend fun waitForLockWaiter() = withContext(Dispatchers.IO) {
        withTimeout(10_000) {
            while (true) {
                val waiting = TestDatabase.database.dataSource.connection.use { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
                            .use { rs -> rs.next(); rs.getInt(1) }
                    }.also { conn.commit() }
                }
                if (waiting > 0) break
                delay(20)
            }
        }
    }

    @Test
    fun `两人同时改同一条待办的不同地方：谁也不盖掉谁`() = serverTest { client ->
        val (aqi, xiaochi, room) = Api(client).pair()
        val todo = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(todo, "买菜"))

        val response = whileSomeoneElseWrites(
            room,
            otherWrite = { Todos.update({ Todos.id eq todo }) { it[Todos.title] = "买菜和水果" } },
            change = { xiaochi.patch("/api/v1/rooms/$room/todos/$todo", UpdateTodoRequest(note = Patch.of("记得带袋子"))) },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val saved = response.body<Todo>()
        assertEquals("买菜和水果", saved.title)
        assertEquals("记得带袋子", saved.note)
    }

    @Test
    fun `两人同时改同一个日程的不同地方：谁也不盖掉谁`() = serverTest { client ->
        val (aqi, xiaochi, room) = Api(client).pair()
        val event = UuidV7.generate()
        aqi.post("/api/v1/rooms/$room/events", CreateEventRequest(event, "看电影", allDay = true, startDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 10, 1)))

        val response = whileSomeoneElseWrites(
            room,
            otherWrite = { Events.update({ Events.id eq event }) { it[Events.location] = "万达" } },
            change = { xiaochi.patch("/api/v1/rooms/$room/events/$event", UpdateEventRequest(title = Patch.of("看电影（IMAX）"))) },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val saved = response.body<Event>()
        assertEquals("看电影（IMAX）", saved.title)
        assertEquals("万达", saved.location)
    }
}
