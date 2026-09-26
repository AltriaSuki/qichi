package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.jobs.JobQueue
import app.qichi.server.serverTest
import app.qichi.server.testConfig
import app.qichi.server.testContext
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.api.WsEvent
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** P11-03：问 AI 时模型自己查资料——多轮往返、引用查到的内容、「正在查」、停下、轮数上限。 */
class AiToolLoopTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    /** 2026-09-24 周四 10:00（房间默认时区 Asia/Shanghai） */
    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))

    /** 按剧本回复的假模型：第 n 轮怎么回由 [script] 决定。 */
    private class ScriptedGateway(private val script: suspend (round: Int, request: AiRequest, onText: suspend (String) -> Unit) -> AiResult) : AiGateway {
        override val model = "fake-tools"
        val requests = mutableListOf<AiRequest>()
        override suspend fun complete(request: AiRequest) = error("应该走流式")
        override suspend fun stream(request: AiRequest, onText: suspend (String) -> Unit): AiResult {
            requests += request
            return script(requests.size - 1, request, onText)
        }
    }

    private fun lookup(name: String, args: String) = AiResult("", 10, 5, "fake-tools", listOf(AiToolCall("call_$name", name, args)))
    private fun answer(text: String) = AiResult(text, 10, 5, "fake-tools")

    @Test
    fun `模型查一次再回答：查到的内容能引用、点开跳到原消息；查的时候推「正在查」`() {
        var cited = 0
        val gateway = ScriptedGateway { round, request, onText ->
            when (round) {
                0 -> {
                    assertTrue(request.tools.any { it.name == "search" } && request.tools.any { it.name == "read_chat" }, request.tools.map { it.name }.toString())
                    lookup("search", """{"query":"民宿"}""")
                }
                else -> {
                    val result = request.messages.last()
                    assertEquals(AiMessage.Role.Tool, result.role)
                    assertEquals("call_search", result.toolCallId)
                    assertEquals(AiMessage.Role.Assistant, request.messages[request.messages.size - 2].role)
                    cited = Regex("""\[(\d+)] 聊天（.*?）：民宿订好了""").find(result.content)!!.groupValues[1].toInt()
                    val text = "民宿订好了，在东山岛[$cited]。"
                    onText(text)
                    answer(text)
                }
            }
        }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            // 聊天够多，民宿那条不在最近 30 条里
            val hotel = aqi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "民宿订好了，在东山岛")).body<Message>()
            repeat(35) { i -> chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "闲聊 $i")) }

            val ws = createClient { install(ClientWebSockets) }
            suspend fun DefaultClientWebSocketSession.next(): WsEvent? = withTimeoutOrNull(5_000) {
                QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            }
            val jobId = UuidV7.generate()
            val events = mutableListOf<WsEvent>()
            ws.webSocket("/api/v1/ws?caps=ai_stream", request = { bearerAuth(chi.tokens.accessToken) }) {
                next() // hello
                aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "住的地方订了吗？"))
                ctx.jobs.drain()
                while (true) {
                    val e = next() ?: break
                    events += e
                    if (e is WsEvent.AiDone) break
                }
            }
            val deltas = events.filterIsInstance<WsEvent.AiDelta>()
            assertEquals("正在查：搜索「民宿」", deltas.first().status, deltas.toString())
            assertEquals("", deltas.first().text)
            assertEquals(null, deltas.last().status, events.toString())
            assertEquals("民宿订好了，在东山岛。", deltas.last().text)

            assertEquals(2, gateway.requests.size)
            val saved = aqi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.first { it.id == jobId }
            assertEquals("民宿订好了，在东山岛[1]。", saved.body)
            val source = saved.aiSources.single()
            assertEquals("message", source.type)
            assertEquals(hotel.id, source.id)
            assertEquals(1, source.number)

            val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
            assertEquals(20, job.inputTokens, "两轮的用量加起来")
            assertEquals(10, job.outputTokens)
        }
    }

    @Test
    fun `一直要查：第 6 轮之后不许再查，还是没写就告诉他们没整理出来`() {
        val gateway = ScriptedGateway { round, _, _ -> lookup("todos", """{"status":"open"}""").copy(toolCalls = listOf(AiToolCall("c$round", "todos", "{}"))) }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "帮我看看都有什么事"))
            ctx.jobs.drain()
            assertEquals(AiService.TOOL_ROUNDS + 1, gateway.requests.size)
            val last = gateway.requests.last().messages.last()
            assertEquals(AiMessage.Role.User, last.role)
            assertTrue(last.content.contains("不能再查了"), last.content)
            val saved = aqi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.first { it.id == jobId }
            assertEquals(AiService.GAVE_UP, saved.body)
        }
    }

    @Test
    fun `查完资料写回答时停下：存下已经写出来的部分`() {
        val writing = CompletableDeferred<Unit>()
        val gateway = ScriptedGateway { round, _, onText ->
            if (round == 0) {
                lookup("events", """{"from":"2026-09-24","to":"2026-09-30"}""")
            } else {
                onText("这周六早上出发")
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "这周有什么安排？"))
            coroutineScope {
                val worker = launch { ctx.jobs.drain() }
                withTimeout(5_000) { writing.await() }
                aqi.post("/api/v1/rooms/$room/ai/jobs/$jobId/stop")
                withTimeout(5_000) { worker.join() }
            }
            val saved = chi.get("/api/v1/rooms/$room/messages").body<MessagePage>().messages.first { it.id == jobId }
            assertTrue(saved.aiStopped)
            assertEquals("这周六早上出发", saved.body)
            val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Done, job.status)
            assertTrue(job.inputTokens > 10, "查资料那一轮按服务商报的，正在写的这一轮按字数估")
        }
    }

    @Test
    fun `意外出错：重试用完后标成失败（App 显示重试，不会一直转圈）`() {
        val gateway = ScriptedGateway { _, _, _ -> error("模型那边返回了看不懂的东西") }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            val jobId = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(jobId, "周六去哪？"))
            ctx.jobs.drain()
            assertEquals(AiJobStatus.Running, aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().status, "第一次失败交给队列重试")
            clock.advance(JobQueue.backoff(1))
            ctx.jobs.drain()
            assertEquals(2, gateway.requests.size)
            assertEquals(AiJobStatus.Failed, aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>().status)
        }
    }

    @Test
    fun `能自己查时只事先备常备的；两个人都允许的类别才提供工具；关掉 AI_TOOLS 就不带工具、备完整的一份`() {
        val gateway = ScriptedGateway { _, _, _ -> answer("好的。") }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/events", CreateEventRequest(UuidV7.generate(), "出发去海边", allDay = false,
                startsAt = Instant.parse("2026-09-26T00:00:00Z"), endsAt = Instant.parse("2026-09-26T02:00:00Z")))
            aqi.post("/api/v1/rooms/$room/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Down, 7, "有点低落"))
            aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(UuidV7.generate(), "去海边看日出"))
            chi.patch("/api/v1/me", UpdateMeRequest(aiPrefs = Patch.of(AiPrefs(ideas = false).toJson())))

            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(UuidV7.generate(), "海边的事怎么样了？"))
            ctx.jobs.drain()
            val request = gateway.requests.single()
            val prompt = request.messages.single().content
            assertTrue(prompt.contains("出发去海边"), prompt)
            assertFalse(prompt.contains("有点低落"), "心情不在常备的里，要 AI 自己查")
            assertTrue(request.system.contains("用工具去房间里查"), request.system)
            val names = request.tools.map { it.name }
            assertTrue("moods" in names && "search" in names, names.toString())
            assertFalse("ideas" in names, "小迟关掉了灵感，谁问都不给")
        }

        val plain = ScriptedGateway { _, _, _ -> answer("好的。") }
        val off = testContext(config = testConfig().let { it.copy(ai = it.ai.copy(tools = false)) }, clock = clock, aiGateway = plain)
        TestDatabase.reset()
        serverTest(off) { client ->
            val (aqi, _, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.Down, 7, "有点低落"))
            aqi.post("/api/v1/rooms/$room/ai/chat", AiChatRequest(UuidV7.generate(), "最近怎么样？"))
            off.jobs.drain()
            val request = plain.requests.single()
            assertTrue(request.tools.isEmpty())
            assertTrue(request.messages.single().content.contains("有点低落"))
            assertFalse(request.system.contains("用工具"))
        }
    }
}
