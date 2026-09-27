package app.qichi.server.plans

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CreatePlanLogRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanDetail
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdatePlanLogRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 计划的管理（P14-03）：进展记录能改能删、先放一放与重新打开、下一步连着待办。 */
class PlanManageTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private suspend fun Session.plan(room: UUID, title: String = "秋天去海边"): Plan =
        post("/api/v1/rooms/$room/plans", CreatePlanRequest(UuidV7.generate(), title, userId())).body()

    private suspend fun Session.todo(room: UUID, request: CreateTodoRequest): Todo =
        post("/api/v1/rooms/$room/todos", request).body()

    private suspend fun Session.planNow(room: UUID, id: UUID): Plan = get("/api/v1/rooms/$room/plans/$id").body<PlanDetail>().plan

    private suspend fun Session.changedSince(room: UUID, seq: Long): SyncResponse = get("/api/v1/rooms/$room/sync?since=$seq").body()

    private suspend fun Session.lastSeq(room: UUID): Long = get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().lastSeq

    @Test fun `进展记录：记的人能改能删，删了进回收站，只有记的人能恢复；对方和非成员不行；改和删都同步`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val plan = aqi.plan(room)
        val logs = "/api/v1/rooms/$room/plans/${plan.id}/logs"
        val id = UuidV7.generate()
        aqi.post(logs, CreatePlanLogRequest(id, "收窄到两个小镇"))
        val seq = chi.lastSeq(room)

        val edited = aqi.patch("$logs/$id", UpdatePlanLogRequest("  收窄到两个小镇：霞浦和东山  ")).body<PlanLog>()
        assertEquals("收窄到两个小镇：霞浦和东山", edited.body, "去掉首尾空白")
        assertEquals(aqi.userId(), edited.authorId, "作者不变")
        assertTrue(chi.changedSince(room, seq).changes.any { it.type == EntityType.PlanLog && it.id == id }, "对方同步得到")

        chi.patch("$logs/$id", UpdatePlanLogRequest("我来改")).assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        chi.delete("$logs/$id").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        aqi.patch("$logs/$id", UpdatePlanLogRequest("  ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.patch("$logs/$id", UpdatePlanLogRequest("长".repeat(10_001))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val outsider = api.outsider(aqi)
        outsider.patch("$logs/$id", UpdatePlanLogRequest("偷改")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.delete("$logs/$id").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        aqi.patch("/api/v1/rooms/$room/plans/${UuidV7.generate()}/logs/$id", UpdatePlanLogRequest("换个计划"))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val beforeDelete = chi.lastSeq(room)
        assertNotNull(aqi.delete("$logs/$id").body<PlanLog>().deletedAt)
        assertEquals(HttpStatusCode.OK, aqi.delete("$logs/$id").status, "重复删除照样 200")
        assertTrue(chi.get("/api/v1/rooms/$room/plans/${plan.id}").body<PlanDetail>().logs.isEmpty(), "详情里不再有")
        assertTrue(chi.changedSince(room, beforeDelete).changes.any { it.type == EntityType.PlanLog && it.id == id })
        aqi.patch("$logs/$id", UpdatePlanLogRequest("删了还改")).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val trash = chi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items
        assertEquals(listOf(TrashType.PlanLog), trash.map { it.type }, "进回收站，两个人都看得到")
        chi.post("/api/v1/rooms/$room/trash/plan_log/$id/restore").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        chi.delete("/api/v1/rooms/$room/trash/plan_log/$id").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        assertEquals(HttpStatusCode.OK, aqi.post("/api/v1/rooms/$room/trash/plan_log/$id/restore").status)
        assertEquals(listOf("收窄到两个小镇：霞浦和东山"), chi.get("/api/v1/rooms/$room/plans/${plan.id}").body<PlanDetail>().logs.map { it.body })

        // 计划整个进了回收站：记录随它一起，不单独出现
        aqi.delete("$logs/$id")
        aqi.delete("/api/v1/rooms/$room/plans/${plan.id}")
        assertEquals(listOf(TrashType.Plan), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        aqi.post("/api/v1/rooms/$room/trash/plan_log/$id/restore").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test fun `先放一放、接着做、重新打开：重新打开清掉完成时间、留着完成记录，再完成时换成新的；已完成的不能直接放一放`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val plan = aqi.plan(room)
        val path = "/api/v1/rooms/$room/plans/${plan.id}"

        assertEquals(PlanStatus.Archived, chi.patch(path, UpdatePlanRequest(status = Patch.of(PlanStatus.Archived))).body<Plan>().status)
        assertEquals(PlanStatus.Archived, aqi.planNow(room, plan.id).status)
        assertEquals(PlanStatus.Active, chi.patch(path, UpdatePlanRequest(status = Patch.of(PlanStatus.Active))).body<Plan>().status)

        val done = aqi.post("$path/complete", CompletePlanRequest("看到了海")).body<Plan>()
        assertNotNull(done.completedAt)
        aqi.patch(path, UpdatePlanRequest(status = Patch.of(PlanStatus.Archived))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.patch(path, UpdatePlanRequest(status = Patch.of(PlanStatus.Done))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)

        val seq = chi.lastSeq(room)
        val reopened = chi.patch(path, UpdatePlanRequest(status = Patch.of(PlanStatus.Active))).body<Plan>()
        assertEquals(PlanStatus.Active, reopened.status)
        assertNull(reopened.completedAt, "完成时间清掉")
        assertEquals("看到了海", reopened.completionNote, "完成记录留着")
        assertTrue(aqi.changedSince(room, seq).changes.any { it.type == EntityType.Plan && it.id == plan.id })

        val again = aqi.post("$path/complete", CompletePlanRequest("第二次去，住了三天")).body<Plan>()
        assertEquals(PlanStatus.Done, again.status)
        assertNotNull(again.completedAt)
        assertEquals("第二次去，住了三天", again.completionNote)
    }

    @Test fun `下一步用计划里的待办：照那件待办填；待办改了跟着变；做完或删掉时下一步结束；移出计划或直接改下一步就断开`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val plan = aqi.plan(room)
        val path = "/api/v1/rooms/$room/plans/${plan.id}"
        val chiId = chi.userId()
        // 只有时刻的截止按房间时区（默认东八区）算日期：UTC 10 月 1 日 17 点是北京时间 10 月 2 日
        val todo = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "订住处", assigneeId = chiId, dueAt = Instant.parse("2026-10-01T17:00:00Z"), planId = plan.id))

        val linked = chi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(todo.id))).body<Plan>()
        assertEquals(todo.id, linked.nextStepTodoId)
        assertEquals("订住处", linked.nextStep)
        assertEquals(chiId, linked.nextStepOwnerId)
        assertEquals(LocalDate.of(2026, 10, 2), linked.nextStepDue)

        // 待办改了名字、交给谁、截止：下一步跟着变，对方同步得到
        val seq = chi.lastSeq(room)
        aqi.patch("/api/v1/rooms/$room/todos/${todo.id}", UpdateTodoRequest(
            title = Patch.of("订霞浦的民宿"), assigneeId = Patch.of(null), dueAt = Patch.of(null), dueDate = Patch.of(LocalDate.of(2026, 10, 5)),
        ))
        chi.planNow(room, plan.id).let {
            assertEquals("订霞浦的民宿", it.nextStep)
            assertNull(it.nextStepOwnerId, "交给两个人")
            assertEquals(LocalDate.of(2026, 10, 5), it.nextStepDue)
            assertEquals(todo.id, it.nextStepTodoId)
        }
        assertTrue(chi.changedSince(room, seq).changes.any { it.type == EntityType.Plan && it.id == plan.id })

        // 做完：下一步结束（旧版 App 也看得到它没了）
        val beforeDone = chi.lastSeq(room)
        chi.post("/api/v1/rooms/$room/todos/${todo.id}/complete", CompleteTodoRequest())
        chi.planNow(room, plan.id).let {
            assertNull(it.nextStep)
            assertNull(it.nextStepOwnerId)
            assertNull(it.nextStepDue)
            assertNull(it.nextStepTodoId)
        }
        assertTrue(aqi.changedSince(room, beforeDone).changes.any { it.type == EntityType.Plan && it.id == plan.id })

        // 删掉：一样结束
        val second = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "查车票", planId = plan.id))
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(second.id)))
        aqi.delete("/api/v1/rooms/$room/todos/${second.id}")
        assertNull(aqi.planNow(room, plan.id).nextStep)

        // 移出这个计划：只断开，文字留着
        val third = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "请好年假", planId = plan.id))
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(third.id)))
        aqi.patch("/api/v1/rooms/$room/todos/${third.id}", UpdateTodoRequest(planId = Patch.of(null)))
        aqi.planNow(room, plan.id).let {
            assertEquals("请好年假", it.nextStep)
            assertNull(it.nextStepTodoId)
        }

        // 直接改下一步（旧版 App 就是这样）：不再跟着待办
        val fourth = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "买防晒", planId = plan.id))
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(fourth.id)))
        val retyped = chi.patch(path, UpdatePlanRequest(nextStep = Patch.of("买防晒和帽子"))).body<Plan>()
        assertEquals("买防晒和帽子", retyped.nextStep)
        assertNull(retyped.nextStepTodoId)
        aqi.post("/api/v1/rooms/$room/todos/${fourth.id}/complete", CompleteTodoRequest())
        assertEquals("买防晒和帽子", aqi.planNow(room, plan.id).nextStep, "断开以后那件待办做完不影响下一步")

        // 断开（null）：下一步的文字留着
        val fifth = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "查天气", planId = plan.id))
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(fifth.id)))
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(null))).body<Plan>().let {
            assertEquals("查天气", it.nextStep)
            assertNull(it.nextStepTodoId)
        }
    }

    @Test fun `下一步只能连这个计划里没做完、没删的待办；连着时不能同时写下一步`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val plan = aqi.plan(room)
        val other = aqi.plan(room, "搬家")
        val path = "/api/v1/rooms/$room/plans/${plan.id}"
        val open = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "订住处", planId = plan.id))
        val elsewhere = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "打包", planId = other.id))
        val loose = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "不属于计划"))
        val done = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "做完了", planId = plan.id))
        aqi.post("/api/v1/rooms/$room/todos/${done.id}/complete", CompleteTodoRequest())
        val deleted = aqi.todo(room, CreateTodoRequest(UuidV7.generate(), "删掉了", planId = plan.id))
        aqi.delete("/api/v1/rooms/$room/todos/${deleted.id}")
        val otherRoom = aqi.createRoom("另一个").room.id
        val foreign = aqi.todo(otherRoom, CreateTodoRequest(UuidV7.generate(), "别的房间"))

        for (id in listOf(elsewhere.id, loose.id, done.id, deleted.id, foreign.id, UuidV7.generate())) {
            aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(id))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        }
        aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(open.id), nextStep = Patch.of("别的")))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        assertNull(aqi.planNow(room, plan.id).nextStepTodoId, "都没连上")
        assertEquals(open.id, aqi.patch(path, UpdatePlanRequest(nextStepTodoId = Patch.of(open.id))).body<Plan>().nextStepTodoId)
    }
}
