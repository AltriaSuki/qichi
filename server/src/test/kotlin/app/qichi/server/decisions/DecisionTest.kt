package app.qichi.server.decisions

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateDecisionRequest
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Patch
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.TrashPage
import app.qichi.shared.api.UpdateDecisionRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.TrashType
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `新建：备选去掉空白和重复；幂等；对方同步得到`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/decisions"
        val req = CreateDecisionRequest(UuidV7.generate(), " 搬到哪里？ ", listOf("离你公司近", " ", "离我公司近", "离你公司近"), LocalDate.of(2026, 12, 1))
        val created = aqi.post(path, req)
        assertEquals(HttpStatusCode.Created, created.status)
        val d = created.body<Decision>()
        assertEquals("搬到哪里？", d.question)
        assertEquals(listOf("离你公司近", "离我公司近"), d.options)
        assertEquals(LocalDate.of(2026, 12, 1), d.reviewDate)
        assertNull(d.finalChoice)
        assertEquals(HttpStatusCode.OK, aqi.post(path, req).status)
        assertEquals(listOf(d.id), chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().decisions.map { it.id })
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes.any { it.type == EntityType.Decision })
    }

    @Test fun `关注点各写各的，互不覆盖；定下来记下时间和人，重新考虑清空`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/decisions"
        val d = aqi.post(path, CreateDecisionRequest(UuidV7.generate(), "搬到哪里？", listOf("A", "B"))).body<Decision>()
        aqi.patch("$path/${d.id}", UpdateDecisionRequest(myConcern = Patch.of("通勤时间")))
        chi.patch("$path/${d.id}", UpdateDecisionRequest(myConcern = Patch.of("  房租预算 ")))
        val both = aqi.patch("$path/${d.id}", UpdateDecisionRequest(myConcern = Patch.of("通勤时间，最好一小时内"))).body<Decision>()
        assertEquals(
            mapOf(aqi.userId() to "通勤时间，最好一小时内", chi.userId() to "房租预算"),
            both.concerns.associate { it.userId to it.text },
        )
        val withdrawn = chi.patch("$path/${d.id}", UpdateDecisionRequest(myConcern = Patch.of(null))).body<Decision>()
        assertEquals(listOf(aqi.userId()), withdrawn.concerns.map { it.userId })

        val decided = chi.patch("$path/${d.id}", UpdateDecisionRequest(finalChoice = Patch.of("A"), reviewDate = Patch.of(LocalDate.of(2027, 3, 1)))).body<Decision>()
        assertEquals("A", decided.finalChoice)
        assertNotNull(decided.decidedAt)
        assertEquals(chi.userId(), decided.decidedBy)
        assertEquals(LocalDate.of(2027, 3, 1), decided.reviewDate)
        val reopened = aqi.patch("$path/${d.id}", UpdateDecisionRequest(finalChoice = Patch.of(null))).body<Decision>()
        assertNull(reopened.finalChoice)
        assertNull(reopened.decidedAt)
        assertNull(reopened.decidedBy)
    }

    @Test fun `参数：问题空白、备选太多或太长 400；非成员 404；删除进回收站可恢复`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val path = "/api/v1/rooms/$room/decisions"
        aqi.post(path, CreateDecisionRequest(UuidV7.generate(), "  ")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post(path, CreateDecisionRequest(UuidV7.generate(), "x", List(11) { "选项$it" })).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post(path, CreateDecisionRequest(UuidV7.generate(), "x", listOf("字".repeat(101)))).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        api.outsider(aqi).get(path).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        val d = aqi.post(path, CreateDecisionRequest(UuidV7.generate(), "周末去哪")).body<Decision>()
        chi.delete("$path/${d.id}")
        assertTrue(aqi.get(path).body<List<Decision>>().isEmpty())
        assertEquals(listOf(TrashType.Decision), aqi.get("/api/v1/rooms/$room/trash").body<TrashPage>().items.map { it.type })
        aqi.post("/api/v1/rooms/$room/trash/decision/${d.id}/restore")
        assertEquals(1, aqi.get(path).body<List<Decision>>().size)
    }
}
