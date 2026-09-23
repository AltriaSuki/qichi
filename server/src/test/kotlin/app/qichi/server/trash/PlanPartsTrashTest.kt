package app.qichi.server.trash

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.CreateMilestoneRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanPartsTrashTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `删掉的阶段、里程碑进回收站可以恢复；计划也删了时它们不单独出现、不能单独恢复`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val plan = UuidV7.generate()
        val base = "/api/v1/rooms/$room/plans/$plan"
        aqi.post("/api/v1/rooms/$room/plans", CreatePlanRequest(plan, "搬家", aqi.userId()))
        val stage = UuidV7.generate()
        aqi.post("$base/stages", CreatePlanStageRequest(stage, "打包", 0))
        val milestone = UuidV7.generate()
        aqi.post("$base/milestones", CreateMilestoneRequest(milestone, "签合同"))
        aqi.delete("$base/stages/$stage")
        aqi.delete("$base/milestones/$milestone")
        val trash = aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>()
        assertEquals(setOf(TrashType.PlanStage, TrashType.Milestone), trash.items.map { it.type }.toSet())
        assertEquals(HttpStatusCode.OK, aqi.post("/api/v1/rooms/$room/trash/plan_stage/$stage/restore").status)

        aqi.delete("$base/stages/$stage")
        aqi.delete(base)
        val after = aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type }
        assertEquals(listOf(TrashType.Plan), after, "计划删了，阶段和里程碑随它一起")
        aqi.post("/api/v1/rooms/$room/trash/milestone/$milestone/restore").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertTrue(aqi.post("/api/v1/rooms/$room/trash/plan/$plan/restore").status == HttpStatusCode.OK)
        assertEquals(setOf(TrashType.PlanStage, TrashType.Milestone), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type }.toSet())
    }
}
