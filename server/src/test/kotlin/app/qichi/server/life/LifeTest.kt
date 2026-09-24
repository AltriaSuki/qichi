package app.qichi.server.life

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.serverTest
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CompleteTodoResponse
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateMoodReplyRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Event
import app.qichi.shared.api.Me
import app.qichi.shared.api.Mood
import app.qichi.shared.api.MoodReply
import app.qichi.shared.api.Patch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNull
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateEventRequest
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.MoodReplyKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private suspend fun app.qichi.server.Session.userId() = get("/api/v1/me").body<Me>().user.id

    // ── 心情（P2-01）──

    @Test
    fun `记录心情：新建 201，同 id 再提交 200，参数不合法 400`() = serverTest { client ->
        val (owner, _, roomId) = Api(client).pair()
        val req = CreateMoodRequest(UuidV7.generate(), MoodLabel.Tired, 6, "今天连开了三个会", needsComfort = true)
        val created = owner.post("/api/v1/rooms/$roomId/moods", req)
        assertEquals(HttpStatusCode.Created, created.status)
        val mood = created.body<Mood>()
        assertEquals(MoodLabel.Tired, mood.label)
        assertTrue(mood.needsComfort)
        assertEquals(HttpStatusCode.OK, owner.post("/api/v1/rooms/$roomId/moods", req).status)

        owner.post("/api/v1/rooms/$roomId/moods", req.copy(id = UuidV7.generate(), intensity = 11))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/moods", mapOf("id" to UuidV7.generate().toString(), "label" to "sleepy", "intensity" to "5"))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `非作者删除心情返回 403，作者删除进回收站`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val mood = owner.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Calm, 3)).body<Mood>()
        member.delete("/api/v1/rooms/$roomId/moods/${mood.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        val deleted = owner.delete("/api/v1/rooms/$roomId/moods/${mood.id}").body<Mood>()
        assertNotNull(deleted.deletedAt)
    }

    @Test
    fun `回应：同 id 重复提交只产生一条；同一种回应只保留一条有效；不能回应自己`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val mood = owner.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Down, 7, needsComfort = true)).body<Mood>()

        val req = CreateMoodReplyRequest(UuidV7.generate(), MoodReplyKind.Hug)
        assertEquals(HttpStatusCode.Created, member.post("/api/v1/rooms/$roomId/moods/${mood.id}/responses", req).status)
        assertEquals(HttpStatusCode.OK, member.post("/api/v1/rooms/$roomId/moods/${mood.id}/responses", req).status)
        val again = member.post("/api/v1/rooms/$roomId/moods/${mood.id}/responses", req.copy(id = UuidV7.generate()))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(req.id, again.body<MoodReply>().id)
        assertEquals(1L, TestDatabase.database.tx { MoodResponses.selectAll().where { MoodResponses.moodId eq mood.id }.count() })

        owner.post("/api/v1/rooms/$roomId/moods/${mood.id}/responses", CreateMoodReplyRequest(UuidV7.generate(), MoodReplyKind.Here))
            .assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)

        // 收回：只能收回自己的；收回后可以再回应一次
        owner.delete("/api/v1/rooms/$roomId/mood-responses/${req.id}").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertNotNull(member.delete("/api/v1/rooms/$roomId/mood-responses/${req.id}").body<MoodReply>().deletedAt)
        assertEquals(
            HttpStatusCode.Created,
            member.post("/api/v1/rooms/$roomId/moods/${mood.id}/responses", CreateMoodReplyRequest(UuidV7.generate(), MoodReplyKind.Hug)).status,
        )
    }

    // ── 待办（P2-02）──

    @Test
    fun `每周日重复的待办完成后生成下周日的新实例，重复提交完成不会生成两个`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val id = UuidV7.generate()
        owner.post(
            "/api/v1/rooms/$roomId/todos",
            CreateTodoRequest(id, "核对预算", dueDate = LocalDate.parse("2026-09-27"), recurrence = "FREQ=WEEKLY;BYDAY=SU"),
        )
        val nextId = UuidV7.generate()
        val done = member.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(nextId)).body<CompleteTodoResponse>()
        assertNotNull(done.todo.doneAt)
        val next = done.next!!
        assertEquals(nextId, next.id)
        assertEquals(LocalDate.parse("2026-10-04"), next.dueDate)
        assertEquals(id, next.recurrencePrevId)
        assertNull(next.doneAt)

        val again = member.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(UuidV7.generate())).body<CompleteTodoResponse>()
        assertEquals(nextId, again.next!!.id)
        assertEquals(1L, TestDatabase.database.tx { Todos.selectAll().where { Todos.recurrencePrevId eq id }.count() })
    }

    @Test
    fun `重复待办取消完成时收回还没动过的下一次；反复勾选也只有一条；下一次被改过就留着`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val id = UuidV7.generate()
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(id, "背单词", dueDate = LocalDate.parse("2026-09-24"), recurrence = "FREQ=DAILY"))
        fun open() = TestDatabase.database.let { db -> kotlinx.coroutines.runBlocking { db.tx { Todos.selectAll().where { (Todos.title eq "背单词") and Todos.doneAt.isNull() }.count() } } }

        repeat(3) {
            owner.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(UuidV7.generate()))
            owner.post("/api/v1/rooms/$roomId/todos/$id/reopen")
        }
        // 只剩最初那一条（没完成），自动生成的下一次都收回了
        assertEquals(1L, open())
        assertEquals(0L, TestDatabase.database.tx { Todos.selectAll().where { Todos.recurrencePrevId eq id }.count() })

        // 下一次被人改过（加了备注）：取消完成时留着
        val next = owner.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(UuidV7.generate())).body<CompleteTodoResponse>().next!!
        member.patch("/api/v1/rooms/$roomId/todos/${next.id}", app.qichi.shared.api.UpdateTodoRequest(note = Patch.of("早上背")))
        owner.post("/api/v1/rooms/$roomId/todos/$id/reopen")
        assertEquals(2L, open())
    }

    @Test
    fun `两台设备同时完成同一个重复待办，也只生成一个下一次`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val id = UuidV7.generate()
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(id, "浇花", dueDate = LocalDate.parse("2026-09-21"), recurrence = "FREQ=DAILY"))
        val results = coroutineScope {
            listOf(owner, member).map { s ->
                async { s.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(UuidV7.generate())).body<CompleteTodoResponse>() }
            }.awaitAll()
        }
        assertEquals(1, results.map { it.next!!.id }.toSet().size)
        assertEquals(1L, TestDatabase.database.tx { Todos.selectAll().where { Todos.recurrencePrevId eq id }.count() })
    }

    @Test
    fun `重复待办必须带 nextId；按时刻截止的重复待办保留房间时区里的钟点`() = serverTest { client ->
        val (owner, _, roomId) = Api(client).pair()
        owner.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(timezone = Patch.of("Asia/Shanghai")))
        val id = UuidV7.generate()
        // 上海时间 2026-09-21 20:00 = UTC 12:00
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(id, "吃药", dueAt = Instant.parse("2026-09-21T12:00:00Z"), recurrence = "FREQ=DAILY"))
        owner.post("/api/v1/rooms/$roomId/todos/$id/complete").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val next = owner.post("/api/v1/rooms/$roomId/todos/$id/complete", CompleteTodoRequest(UuidV7.generate())).body<CompleteTodoResponse>().next!!
        assertEquals(Instant.parse("2026-09-22T12:00:00Z"), next.dueAt)
        assertEquals(20, next.dueAt!!.atZone(ZoneId.of("Asia/Shanghai")).hour)
    }

    @Test
    fun `修改、取消完成；校验指派人、截止、重复规则`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val memberId = member.userId()
        val id = UuidV7.generate()
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(id, "查往返车次"))
        val updated = owner.patch("/api/v1/rooms/$roomId/todos/$id", UpdateTodoRequest(assigneeId = Patch.of(memberId), dueDate = Patch.of(LocalDate.parse("2026-09-24")))).body<Todo>()
        assertEquals(memberId, updated.assigneeId)
        assertEquals(LocalDate.parse("2026-09-24"), updated.dueDate)
        val cleared = owner.patch("/api/v1/rooms/$roomId/todos/$id", UpdateTodoRequest(assigneeId = Patch.of(null), dueDate = Patch.of(null))).body<Todo>()
        assertNull(cleared.assigneeId)
        assertNull(cleared.dueDate)

        owner.post("/api/v1/rooms/$roomId/todos/$id/complete")
        assertNull(owner.post("/api/v1/rooms/$roomId/todos/$id/reopen").body<Todo>().doneAt)

        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "x", assigneeId = UuidV7.generate()))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "x", dueDate = LocalDate.now(), dueAt = Instant.now()))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "x", recurrence = "FREQ=DAILY"))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "x", dueDate = LocalDate.now(), recurrence = "FREQ=YEARLY"))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), " "))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `子任务：只有一层；删除父待办时子任务一起进回收站`() = serverTest { client ->
        val (owner, _, roomId) = Api(client).pair()
        val parent = UuidV7.generate()
        val child = UuidV7.generate()
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(parent, "整理行李清单"))
        assertEquals(HttpStatusCode.Created, owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(child, "衣物", parentId = parent)).status)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "孙任务", parentId = child))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "重复子任务", parentId = parent, dueDate = LocalDate.now(), recurrence = "FREQ=DAILY"))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val deleted = owner.delete("/api/v1/rooms/$roomId/todos/$parent").body<Todo>()
        assertNotNull(deleted.deletedAt)
        val childRow = TestDatabase.database.tx { Todos.selectAll().where { Todos.id eq child }.single()[Todos.deletedAt] }
        assertEquals(deleted.deletedAt, childRow)
    }

    // ── 日程（P2-03）──

    @Test
    fun `日程：定时与全天，形态不对或结束早于开始时 400，修改可以在两种形态间切换`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val id = UuidV7.generate()
        val timed = owner.post(
            "/api/v1/rooms/$roomId/events",
            CreateEventRequest(id, "晚饭", allDay = false, startsAt = Instant.parse("2026-09-21T11:30:00Z"), endsAt = Instant.parse("2026-09-21T13:00:00Z"), location = "海边"),
        )
        assertEquals(HttpStatusCode.Created, timed.status)
        assertEquals("海边", timed.body<Event>().location)

        owner.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "x", allDay = true, startsAt = Instant.now(), endsAt = Instant.now()))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "x", allDay = false, startsAt = Instant.parse("2026-09-21T13:00:00Z"), endsAt = Instant.parse("2026-09-21T12:00:00Z")))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "x", allDay = true, startDate = LocalDate.parse("2026-09-27"), endDate = LocalDate.parse("2026-09-26")))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val switched = member.patch(
            "/api/v1/rooms/$roomId/events/$id",
            UpdateEventRequest(
                allDay = Patch.of(true), startsAt = Patch.of(null), endsAt = Patch.of(null),
                startDate = Patch.of(LocalDate.parse("2026-09-26")), endDate = Patch.of(LocalDate.parse("2026-09-27")),
            ),
        ).body<Event>()
        assertTrue(switched.allDay)
        assertNull(switched.startsAt)
        assertEquals(LocalDate.parse("2026-09-27"), switched.endDate)

        member.patch("/api/v1/rooms/$roomId/events/$id", UpdateEventRequest(allDay = Patch.of(false)))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "x", allDay = true, startDate = LocalDate.now(), endDate = LocalDate.now(), participantIds = listOf(UuidV7.generate())))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        assertNotNull(owner.delete("/api/v1/rooms/$roomId/events/$id").body<Event>().deletedAt)
    }

    @Test
    fun `非成员访问心情、待办、日程接口都是 404`() = serverTest { client ->
        val api = Api(client)
        val (owner, _, roomId) = api.pair()
        val other = owner.createRoom("另一个").room.id
        val code = owner.post("/api/v1/rooms/$other/invites").body<app.qichi.shared.api.Invite>().code
        val outsider = api.registerOk("outsider", code)
        outsider.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Calm, 3))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "x"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "x", allDay = true, startDate = LocalDate.now(), endDate = LocalDate.now()))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }
}
