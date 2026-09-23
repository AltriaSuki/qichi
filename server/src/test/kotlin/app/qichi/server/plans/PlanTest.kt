package app.qichi.server.plans

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.api.Todo
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.CompleteTodoResponse
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CreateMilestoneRequest
import app.qichi.shared.api.CreatePlanLogRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanDetail
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateMilestoneRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdatePlanStageRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `计划可无截止日期创建，字段级修改、完成与同步`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/plans"
        val id = UuidV7.generate()
        val owner = chi.userId()
        val request = CreatePlanRequest(id, "秋天去海边", owner, nextStep = "选地方")
        assertEquals(HttpStatusCode.Created, aqi.post(path, request).status)
        assertEquals(HttpStatusCode.OK, aqi.post(path, request).status)
        assertEquals(null, chi.get(path).body<List<Plan>>().single().targetDate)
        val updated = chi.patch("$path/$id", UpdatePlanRequest(
            targetDate = Patch.of(LocalDate.of(2026, 10, 1)), nextStep = Patch.of("订住处"),
        )).body<Plan>()
        assertEquals("秋天去海边", updated.title)
        assertEquals("订住处", updated.nextStep)
        assertEquals(LocalDate.of(2026, 10, 1), updated.targetDate)
        val done = aqi.post("$path/$id/complete", CompletePlanRequest("我们一起看到了海。" )).body<Plan>()
        assertEquals(PlanStatus.Done, done.status)
        assertNotNull(done.completedAt)
        val seq = aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().lastSeq
        aqi.post("$path/$id/complete", CompletePlanRequest("我们一起看到了海。"))
        assertEquals(seq, aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().lastSeq)
        assertEquals(done, chi.get("$path/$id").body<PlanDetail>().plan)
        assertTrue(chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().plans.any { it.id == id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
            .any { it.type == EntityType.Plan && it.id == id })
    }

    @Test fun `阶段、里程碑与过程记录支持创建、更新和重复提交`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/plans"
        val plan = aqi.post(path, CreatePlanRequest(UuidV7.generate(), "去海边", aqi.userId())).body<Plan>()
        val root = "$path/${plan.id}"
        val stageId = UuidV7.generate()
        val stage = CreatePlanStageRequest(stageId, "选地方", 0)
        assertEquals(HttpStatusCode.Created, aqi.post("$root/stages", stage).status)
        assertEquals(HttpStatusCode.OK, aqi.post("$root/stages", stage).status)
        chi.patch("$root/stages/$stageId", UpdatePlanStageRequest(doneAt = Patch.of(plan.createdAt)))
        val milestoneId = UuidV7.generate()
        val milestone = CreateMilestoneRequest(milestoneId, "订住处", LocalDate.of(2026, 10, 2))
        assertEquals(HttpStatusCode.Created, chi.post("$root/milestones", milestone).status)
        aqi.patch("$root/milestones/$milestoneId", UpdateMilestoneRequest(doneAt = Patch.of(plan.createdAt)))
        val logId = UuidV7.generate()
        assertEquals(HttpStatusCode.Created, aqi.post("$root/logs", CreatePlanLogRequest(logId, "收窄到两个小镇")).status)
        assertEquals(HttpStatusCode.OK, aqi.post("$root/logs", CreatePlanLogRequest(logId, "收窄到两个小镇")).status)
        val detail = chi.get(root).body<PlanDetail>()
        assertEquals(listOf(stageId), detail.stages.map { it.id })
        assertNotNull(detail.stages.single().doneAt)
        assertEquals(listOf(milestoneId), detail.milestones.map { it.id })
        assertNotNull(detail.milestones.single().doneAt)
        assertEquals(listOf(logId), detail.logs.map { it.id })
        assertEquals(1, chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().planLogs.size)
    }

    @Test fun `待办可属于计划；重复待办完成后下一次仍属于这个计划；计划不存在时拒绝`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val plan = aqi.post("/api/v1/rooms/$room/plans", CreatePlanRequest(UuidV7.generate(), "秋天去海边", aqi.userId())).body<Plan>()
        val todo = aqi.post(
            "/api/v1/rooms/$room/todos",
            CreateTodoRequest(UuidV7.generate(), "每周看一次车票", dueDate = LocalDate.of(2026, 9, 27), recurrence = "FREQ=WEEKLY;INTERVAL=1;BYDAY=SU", planId = plan.id),
        ).body<Todo>()
        assertEquals(plan.id, todo.planId)
        val result = aqi.post("/api/v1/rooms/$room/todos/${todo.id}/complete", CompleteTodoRequest(UuidV7.generate())).body<CompleteTodoResponse>()
        assertEquals(plan.id, result.next!!.planId, "下一次实例仍属于计划")

        // 移出计划
        val moved = aqi.patch("/api/v1/rooms/$room/todos/${result.next!!.id}", UpdateTodoRequest(planId = Patch.of(null))).body<Todo>()
        assertEquals(null, moved.planId)
        aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(UuidV7.generate(), "x", planId = UuidV7.generate()))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test fun `非成员访问隐藏资源，删除进回收站并可恢复或彻底删除`() = serverTest { client ->
        val api = Api(client)
        val (aqi, _, room) = api.pair()
        val outsider = api.outsider(aqi)
        val path = "/api/v1/rooms/$room/plans"
        val id = UuidV7.generate()
        outsider.get(path).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post(path, CreatePlanRequest(id, "偷建", aqi.userId()))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        val plan = aqi.post(path, CreatePlanRequest(id, "真实计划", aqi.userId())).body<Plan>()
        outsider.get("$path/$id").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.patch("$path/$id", UpdatePlanRequest(title = Patch.of("偷改")))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        val stageId = UuidV7.generate()
        aqi.post("$path/$id/stages", CreatePlanStageRequest(stageId, "准备", 0))
        aqi.delete("$path/$id")
        assertTrue(aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.any { it.id == id })
        aqi.post("/api/v1/rooms/$room/trash/plan/$id/restore")
        assertEquals(plan.title, aqi.get("$path/$id").body<PlanDetail>().plan.title)
        assertEquals(stageId, aqi.get("$path/$id").body<PlanDetail>().stages.single().id)
        aqi.delete("$path/$id")
        aqi.delete("/api/v1/rooms/$room/trash/plan/$id")
        val changes = aqi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
        assertTrue(changes.any { it.type == EntityType.Plan && it.id == id && it.data == null })
        assertTrue(changes.any { it.type == EntityType.PlanStage && it.id == stageId && it.data == null })
    }
}
