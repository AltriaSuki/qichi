package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Me
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.Patch
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 生活助手第一步：AI 知道「现在」和房间资料，回答带引用；每人可以关掉某几类。 */
class AiContextTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val gateway = FakeGateway()

    /** 2026-09-24 周四 10:00（房间默认时区 Asia/Shanghai） */
    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))

    @Test
    fun `问题里的词：中文两字一切，去掉太常见的`() {
        val t = RoomContext.terms("我们上次说好的预算是多少？Costco 呢")
        assertTrue("预算" in t, t.toString())
        assertTrue("costco" in t)
        assertFalse("我们" in t)
        assertFalse("上次" in t)
    }

    @Test
    fun `问 AI 带上日期和房间资料；回答里的 n 存成可以点开的来源`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            val xiaochiId = xiaochi.get("/api/v1/me").body<Me>().user.id
            val event = aqi.post("/api/v1/rooms/$roomId/events", CreateEventRequest(UuidV7.generate(), "出发去海边", allDay = false,
                location = "东山岛", startsAt = Instant.parse("2026-09-26T00:00:00Z"), endsAt = Instant.parse("2026-09-26T02:00:00Z"))).body<app.qichi.shared.api.Event>()
            aqi.post("/api/v1/rooms/$roomId/todos", CreateTodoRequest(UuidV7.generate(), "带外套", assigneeId = xiaochiId, dueDate = LocalDate.parse("2026-09-26")))
            aqi.post("/api/v1/rooms/$roomId/archive", CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Consensus, "旅行预算", "一次出游不超过两千"))
            xiaochi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "海边看日出要带相机"))
            xiaochi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "阳台种柠檬树"))
            xiaochi.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Tired, 6, "加班"))
            // 更早的聊天（不在最近 30 条里）按问题找回来
            val old = xiaochi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", "海边那家民宿要提前一周订")).body<app.qichi.shared.api.Message>()
            repeat(30) { aqi.post("/api/v1/rooms/$roomId/messages", SendMessageRequest(UuidV7.generate(), "text", "闲聊 $it")) }

            gateway.answer = "周六早上八点出发 [1]，民宿记得提前订 [6]。[99]"
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(jobId, "周六去海边要准备什么？"))
            ctx.jobs.drain()

            val user = gateway.requests.single().messages.single().content
            assertTrue(user.contains("现在是 2026年9月24日 周四 10:00（房间时区 Asia/Shanghai）"), user)
            assertTrue(user.contains("aqi（提问的人）"), user)
            assertTrue(user.contains("[1] 日程 · 9月26日（周六） 08:00 · 出发去海边 · 在东山岛"), user)
            assertTrue(user.contains("待办 · 带外套 · 给xiaochi · 9月26日（周六）前 · 没做完"), user)
            assertTrue(user.contains("档案 · 共识 · 旅行预算：一次出游不超过两千"), user)
            assertTrue(user.contains("灵感") && user.contains("海边看日出要带相机"), "按「海边」找到灵感")
            assertFalse(user.contains("阳台种柠檬树"), "和问题无关的灵感不给")
            assertTrue(user.contains("心情") && user.contains("疲惫 6/10，加班"), "心情用中文，模型才不会照抄英文值")
            assertTrue(user.contains("海边那家民宿要提前一周订"), "更早的聊天按问题找回来")

            val numbered = Regex("(?m)^\\[(\\d+)] (.+)$").findAll(user).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals(6, numbered.size, "日程、待办、心情、档案、灵感、聊天各一条：$numbered")
            assertTrue(numbered[6]!!.startsWith("聊天"), "按类别排，聊天在最后")
            val answer = xiaochi.get("/api/v1/rooms/$roomId/messages").body<MessagePage>().messages.first()
            assertEquals(jobId, answer.id)
            assertEquals("周六早上八点出发 [1]，民宿记得提前订 [2]。", answer.body, "按出现顺序重新编号，不存在的编号去掉")
            assertEquals(listOf(1, 2), answer.aiSources.map { it.number })
            assertEquals("event", answer.aiSources[0].type)
            assertEquals(event.id, answer.aiSources[0].id)
            assertEquals("message", answer.aiSources[1].type)
            assertEquals(old.id, answer.aiSources[1].id)
        }
    }

    @Test
    fun `AI 能看什么：默认都能看；任何一人关掉的类别，两个人问 AI 都看不到`() {
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, xiaochi, roomId) = Api(client).pair()
            assertEquals(AiPrefs(), AiPrefs.from(aqi.get("/api/v1/me").body<Me>().user.aiPrefs))
            aqi.post("/api/v1/rooms/$roomId/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Down, 7, "有点低落"))
            aqi.post("/api/v1/rooms/$roomId/archive", CreateArchiveItemRequest(UuidV7.generate(), ArchiveKind.Boundary, "不聊前任"))

            val me = aqi.patch("/api/v1/me", UpdateMeRequest(aiPrefs = Patch.of(AiPrefs(moods = false, archive = false).toJson()))).body<Me>()
            assertEquals(AiPrefs(moods = false, archive = false), AiPrefs.from(me.user.aiPrefs))

            aqi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "最近怎么样？"))
            ctx.jobs.drain()
            val mine = gateway.requests.last().messages.single().content
            assertFalse(mine.contains("有点低落"), mine)
            assertFalse(mine.contains("不聊前任"), mine)

            xiaochi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "最近怎么样？"))
            ctx.jobs.drain()
            val theirs = gateway.requests.last().messages.single().content
            assertFalse(theirs.contains("有点低落"), theirs)
            assertFalse(theirs.contains("不聊前任"), theirs)

            // 重新打开后又能看到
            aqi.patch("/api/v1/me", UpdateMeRequest(aiPrefs = Patch.of(AiPrefs().toJson())))
            xiaochi.post("/api/v1/rooms/$roomId/ai/chat", AiChatRequest(UuidV7.generate(), "最近怎么样？"))
            ctx.jobs.drain()
            val again = gateway.requests.last().messages.single().content
            assertTrue(again.contains("有点低落"), again)
            assertTrue(again.contains("不聊前任"), again)
        }
    }
}
