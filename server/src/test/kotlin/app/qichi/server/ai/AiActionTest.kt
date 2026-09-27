package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AcceptAiActionRequest
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanDetail
import app.qichi.shared.api.Me
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P8-02：AI 提议、人确认。 */
class AiActionTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway()

    /** 2026-09-24 周四 10:00（Asia/Shanghai） */
    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))
    private val zone = ZoneId.of("Asia/Shanghai")

    private val aqiId = UUID.randomUUID()
    private val chiId = UUID.randomUUID()
    private val planId = UUID.randomUUID()
    private val parser = AiActionParser(zone, mapOf("阿栖" to aqiId, "小迟" to chiId), mapOf("搬家" to planId))

    @Test
    fun `解析：拆出文字和动作，名字、计划、时间都换好`() {
        val parsed = parser.parse(
            """
            周六早上八点出发，记得带外套。
            <actions>
            [{"kind":"event","title":"出发去海边","date":"2026-09-26","time":"08:00","location":"东山岛"},
             {"kind":"todo","title":"带外套","assignee":"小迟","due_date":"2026-09-26"},
             {"kind":"todo","title":"打包","due_date":"2026-09-25","due_time":"21:00","plan":"搬家"},
             {"kind":"event","title":"露营","date":"2026-10-01","end_date":"2026-10-03"},
             {"kind":"archive","type":"preference","title":"小迟怕冷","body":"出门多带一件"},
             {"kind":"idea","body":"海边看日出"}]
            </actions>
            """.trimIndent(),
        )
        assertEquals("周六早上八点出发，记得带外套。", parsed.text)
        assertEquals(5, parsed.actions.size, "最多 5 个")
        val (event, coat, pack, camp, archive) = parsed.actions
        assertEquals(AiActionKind.Event, event.kind)
        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), event.draft.startsAt)
        assertEquals(Instant.parse("2026-09-26T01:00:00Z"), event.draft.endsAt, "没写结束就一小时")
        assertEquals("东山岛", event.draft.location)
        assertFalse(event.draft.allDay)
        assertEquals(chiId, coat.draft.assigneeId)
        assertEquals(LocalDate.parse("2026-09-26"), coat.draft.dueDate)
        assertEquals(Instant.parse("2026-09-25T13:00:00Z"), pack.draft.dueAt)
        assertNull(pack.draft.dueDate)
        assertEquals(planId, pack.draft.planId)
        assertTrue(camp.draft.allDay)
        assertEquals(LocalDate.parse("2026-10-03"), camp.draft.endDate)
        assertEquals(AiActionKind.ArchiveItem, archive.kind)
        assertEquals(ArchiveKind.Preference, archive.draft.archiveKind)
        assertEquals("出门多带一件", archive.draft.note)
    }

    @Test
    fun `解析：没有动作段、JSON 坏了、缺字段的条目都不出错`() {
        assertEquals(ParsedAnswer("就是普通的回答", emptyList()), parser.parse("就是普通的回答"))
        assertEquals(ParsedAnswer("好的", emptyList()), parser.parse("好的\n<actions>[{坏了</actions>"))
        val lenient = parser.parse("好的\n<actions>\n[{\"kind\":\"event\",\"title\":\"没日期\"},{\"kind\":\"todo\",\"title\":\"买菜\",\"assignee\":\"不认识的人\"},{\"kind\":\"what\"}]")
        assertEquals("好的", lenient.text, "没写结束标记也能拆开")
        assertEquals(listOf("买菜"), lenient.actions.map { it.draft.title })
        assertNull(lenient.actions.single().draft.assigneeId, "认不出的名字不指派")
    }

    private suspend fun Session.actions(room: UUID): List<AiAction> =
        get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().aiActions.sortedBy { it.position }

    @Test
    fun `问 AI 的回答带上提议；点好才建成实体，重试不多建；不用就收起`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            val chi = xiaochi.get("/api/v1/me").body<Me>().user
            gateway.answer = """
                周六（9月26日）8 点出发，记得带外套。
                <actions>
                [{"kind":"event","title":"出发去海边","date":"2026-09-26","time":"08:00"},
                 {"kind":"todo","title":"带外套","assignee":"${chi.displayName}"},
                 {"kind":"idea","body":"海边看日出"}]
                </actions>
            """.trimIndent()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, "周六早上八点出发，记得带外套"))
            ctx.jobs.drain()

            val system = gateway.requests.single().system
            assertTrue(system.contains("<actions>"), "提示词里说明了怎么提议")
            val answer = aqi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.first()
            assertEquals("周六（9月26日）8 点出发，记得带外套。", answer.body, "动作段不进正文")

            val proposed = xiaochi.actions(roomId)
            assertEquals(listOf(AiActionKind.Event, AiActionKind.Todo, AiActionKind.Idea), proposed.map { it.kind })
            assertTrue(proposed.all { it.status == AiActionStatus.Proposed && it.messageId == jobId && it.resultId == null })
            assertEquals(chi.id, proposed[1].draft.assigneeId)

            // 小迟点「好」：日程建成，建的人是小迟
            val eventId = UuidV7.generate()
            val accepted = xiaochi.post("/api/v1/rooms/$roomId/ai-actions/${proposed[0].id}/accept", AcceptAiActionRequest(eventId)).body<AiAction>()
            assertEquals(AiActionStatus.Accepted, accepted.status)
            assertEquals(eventId, accepted.resultId)
            assertEquals(chi.id, accepted.decidedBy)
            val events = aqi.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().events
            val event = events.single()
            assertEquals(eventId, event.id)
            assertEquals("出发去海边", event.title)
            assertEquals(Instant.parse("2026-09-26T00:00:00Z"), event.startsAt)
            assertEquals(chi.id, event.createdBy)

            // 重试 / 对方同时点：原样返回，不会多一个日程
            val again = aqi.post("/api/v1/rooms/$roomId/ai-actions/${proposed[0].id}/accept", AcceptAiActionRequest(UuidV7.generate())).body<AiAction>()
            assertEquals(eventId, again.resultId)
            assertEquals(1, aqi.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().events.size)

            // 待办：指派给小迟
            val todoId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$roomId/ai-actions/${proposed[1].id}/accept", AcceptAiActionRequest(todoId))
            val todo = aqi.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().todos.single()
            assertEquals(todoId, todo.id)
            assertEquals(chi.id, todo.assigneeId)

            // 不用：收起，不建灵感；已经接受的点「不用」不变
            val dismissed = aqi.post("/api/v1/rooms/$roomId/ai-actions/${proposed[2].id}/dismiss").body<AiAction>()
            assertEquals(AiActionStatus.Dismissed, dismissed.status)
            assertTrue(aqi.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().ideas.isEmpty())
            assertEquals(AiActionStatus.Accepted, aqi.post("/api/v1/rooms/$roomId/ai-actions/${proposed[0].id}/dismiss").body<AiAction>().status)

            // 房间外的人：404
            val outsider = Api(client).outsider(aqi)
            outsider.post("/api/v1/rooms/$roomId/ai-actions/${proposed[2].id}/accept", AcceptAiActionRequest(UuidV7.generate()))
                .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        }
    }

    @Test
    fun `让 AI 整理一条消息：提示词里带上那条消息；加进计划`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            val aqiId = aqi.get("/api/v1/me").body<Me>().user.id
            val plan = aqi.post("/api/v1/rooms/$roomId/plans", CreatePlanRequest(UuidV7.generate(), "搬家", aqiId)).body<app.qichi.shared.api.Plan>()
            val msg = xiaochi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", "周五晚上九点前把书打包好")).body<Message>()
            gateway.answer = "整理了一个待办。\n<actions>[{\"kind\":\"todo\",\"title\":\"把书打包好\",\"due_date\":\"2026-09-25\",\"due_time\":\"21:00\",\"plan\":\"搬家\"}]</actions>"
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "整理这条消息", sourceMessageId = msg.id))
            ctx.jobs.drain()

            val user = gateway.requests.single().messages.single().content
            assertTrue(user.contains("要整理的消息（xiaochi，9月24日 10:00）：周五晚上九点前把书打包好"), user)
            val action = aqi.actions(roomId).single()
            assertEquals(plan.id, action.draft.planId)
            assertEquals(Instant.parse("2026-09-25T13:00:00Z"), action.draft.dueAt)

            val todoId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$roomId/ai-actions/${action.id}/accept", AcceptAiActionRequest(todoId))
            assertEquals(plan.id, aqi.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().todos.single { it.id == todoId }.planId)

            // 撤回了的、别的房间的消息不能整理
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "整理这条消息", sourceMessageId = UuidV7.generate()))
                .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        }
    }

    @Test
    fun `解析计划相关的提议（P14-04）：认不出计划的丢掉，新计划和已有的同名时不提议`() {
        val parsed = parser.parse(
            """
            好的。
            <actions>
            [{"kind":"plan","title":"学做饭","owner":"小迟","target_date":"2026-12-31","next_step":"买一口铸铁锅"},
             {"kind":"plan","title":"搬家"},
             {"kind":"plan_stage","plan":"搬家","title":"收尾"},
             {"kind":"milestone","plan":"搬家","title":"签合同","date":"2026-10-03"},
             {"kind":"plan_log","plan":"不存在的计划","body":"没有这个计划"},
             {"kind":"next_step","plan":"搬家","title":"约周六看房","assignee":"阿栖","due_date":"2026-09-26"},
             {"kind":"plan_log","plan":"搬家","body":"看了三处房子"}]
            </actions>
            """.trimIndent(),
        )
        assertEquals(
            listOf(AiActionKind.Plan, AiActionKind.PlanStage, AiActionKind.Milestone, AiActionKind.NextStep, AiActionKind.PlanLog),
            parsed.actions.map { it.kind },
            "同名的新计划、认不出计划的进展都丢掉",
        )
        val (newPlan, stage, milestone, step, log) = parsed.actions.map { it.draft }
        assertEquals("学做饭", newPlan.title)
        assertEquals(chiId, newPlan.assigneeId)
        assertEquals(LocalDate.parse("2026-12-31"), newPlan.dueDate)
        assertEquals("买一口铸铁锅", newPlan.nextStep)
        assertEquals(planId, stage.planId)
        assertEquals(LocalDate.parse("2026-10-03"), milestone.dueDate)
        assertEquals("约周六看房", step.title)
        assertEquals(aqiId, step.assigneeId)
        assertEquals(LocalDate.parse("2026-09-26"), step.dueDate)
        assertEquals("看了三处房子", log.title)
        assertEquals(planId, log.planId)
    }

    @Test
    fun `计划相关的提议点「好」才建成（P14-04）：新计划、阶段加在最后、里程碑、进展记在点的人名下、换掉下一步；计划删了就 404`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            val aqiUser = aqi.get("/api/v1/me").body<Me>().user
            val chi = xiaochi.get("/api/v1/me").body<Me>().user
            val plan = aqi.post("/api/v1/rooms/$roomId/plans", CreatePlanRequest(UuidV7.generate(), "搬家", aqiUser.id, nextStep = "打电话问房东")).body<Plan>()
            aqi.post("/api/v1/rooms/$roomId/plans/${plan.id}/stages", CreatePlanStageRequest(UuidV7.generate(), "找房", 0))
            gateway.answer = """
                可以这样推进。
                <actions>
                [{"kind":"plan","title":"学做饭","owner":"${chi.displayName}","target_date":"2026-12-31","next_step":"买一口铸铁锅"},
                 {"kind":"plan_stage","plan":"搬家","title":"打包"},
                 {"kind":"milestone","plan":"搬家","title":"签合同","date":"2026-10-03"},
                 {"kind":"plan_log","plan":"搬家","body":"看了三处房子，第二处最好"},
                 {"kind":"next_step","plan":"搬家","title":"约周六看房","assignee":"${aqiUser.displayName}","due_date":"2026-09-26"}]
                </actions>
            """.trimIndent()
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "搬家接下来怎么弄？顺便我想学做饭"))
            ctx.jobs.drain()
            assertTrue(gateway.requests.single().system.contains("\"kind\":\"plan_stage\""), "提示词里说明了计划相关的提议")

            val proposed = xiaochi.actions(roomId)
            assertEquals(listOf(AiActionKind.Plan, AiActionKind.PlanStage, AiActionKind.Milestone, AiActionKind.PlanLog, AiActionKind.NextStep), proposed.map { it.kind })
            val ids = proposed.associate { it.kind to UuidV7.generate() }
            proposed.forEach { a -> xiaochi.post("/api/v1/rooms/$roomId/ai-actions/${a.id}/accept", AcceptAiActionRequest(ids.getValue(a.kind))) }

            val newPlan = aqi.get("/api/v1/rooms/$roomId/plans/${ids.getValue(AiActionKind.Plan)}").body<PlanDetail>().plan
            assertEquals("学做饭", newPlan.title)
            assertEquals(chi.id, newPlan.ownerId)
            assertEquals(LocalDate.parse("2026-12-31"), newPlan.targetDate)
            assertEquals("买一口铸铁锅", newPlan.nextStep)

            val moving = aqi.get("/api/v1/rooms/$roomId/plans/${plan.id}").body<PlanDetail>()
            assertEquals(listOf("找房", "打包"), moving.stages.map { it.title }, "新阶段加在最后")
            assertEquals(listOf(0, 1), moving.stages.map { it.sortOrder })
            assertEquals(ids.getValue(AiActionKind.Milestone), moving.milestones.single().id)
            assertEquals(LocalDate.parse("2026-10-03"), moving.milestones.single().targetDate)
            val log = moving.logs.single()
            assertEquals("看了三处房子，第二处最好", log.body)
            assertEquals(chi.id, log.authorId, "记在点「好」的人名下")
            assertEquals("约周六看房", moving.plan.nextStep)
            assertEquals(aqiUser.id, moving.plan.nextStepOwnerId)
            assertEquals(LocalDate.parse("2026-09-26"), moving.plan.nextStepDue)
            assertEquals(plan.id, xiaochi.actions(roomId).single { it.kind == AiActionKind.NextStep }.resultId, "设下一步记的是那个计划")

            // 计划删掉以后，关于它的提议接受不了
            gateway.answer = "好。\n<actions>[{\"kind\":\"plan_log\",\"plan\":\"搬家\",\"body\":\"搬完了\"}]</actions>"
            val job = UuidV7.generate()
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(job, "记一下搬完了"))
            ctx.jobs.drain()
            val late = aqi.actions(roomId).single { it.messageId == job }
            aqi.delete("/api/v1/rooms/$roomId/plans/${plan.id}")
            aqi.post("/api/v1/rooms/$roomId/ai-actions/${late.id}/accept", AcceptAiActionRequest(UuidV7.generate()))
                .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            assertEquals(AiActionStatus.Proposed, aqi.actions(roomId).single { it.messageId == job }.status, "没建成就还是提议")
        }
    }
}
