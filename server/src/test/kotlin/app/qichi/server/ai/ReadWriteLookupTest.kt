package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.serverTest
import app.qichi.server.testConfig
import app.qichi.server.testContext
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.AiReadExplainRequest
import app.qichi.shared.api.AiWriteRequest
import app.qichi.shared.api.Book
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Patch
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.DraftGenre
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.model.WriteAssistMode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P14-04：阅读里请 AI、写作助手起草时，AI 也能自己查房间资料，和问 AI 一样守「AI 能看什么」。 */
class ReadWriteLookupTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    /** 2026-09-24 周四 10:00（Asia/Shanghai） */
    private val clock = MutableClock(Instant.parse("2026-09-24T02:00:00Z"))

    /** 按剧本回复的假模型（不流式）：第 n 轮怎么回由 [script] 决定。 */
    private class ScriptedGateway(private val script: (round: Int, request: AiRequest) -> AiResult) : AiGateway {
        override val model = "fake-tools"
        val requests = mutableListOf<AiRequest>()
        override suspend fun complete(request: AiRequest): AiResult {
            requests += request
            return script(requests.size - 1, request)
        }
    }

    private fun lookup(name: String, args: String) = AiResult("", 10, 5, "fake-tools", listOf(AiToolCall("call_$name", name, args)))
    private fun answer(text: String) = AiResult(text, 10, 5, "fake-tools")

    private fun epub(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry() }
    }.toByteArray()

    private suspend fun Session.addBook(room: UUID): Book {
        val file = upload(room, epub(), fileName = "b.epub", kind = "epub", contentType = "application/epub+zip").body<FileMeta>()
        return post("/api/v1/rooms/$room/books", CreateBookRequest(UuidV7.generate(), file.id, "海边的旅店", "林晚")).body()
    }

    private suspend fun Session.explain(room: UUID, book: Book): UUID {
        val jobId = UuidV7.generate()
        post("/api/v1/rooms/$room/ai/read-explain", AiReadExplainRequest(jobId, book.id, ReadExplainMode.Explain, "先在这里坐一会儿", "{}"))
        return jobId
    }

    @Test fun `阅读里请 AI：要联系到他们自己时查房间资料，回答存在标记旁边，不带编号，用量几轮加起来`() {
        val gateway = ScriptedGateway { round, request ->
            when (round) {
                0 -> {
                    assertTrue(request.tools.any { it.name == "search" }, request.tools.map { it.name }.toString())
                    assertTrue(request.system.contains("可以用工具去房间里查"), request.system)
                    lookup("search", """{"query":"海边"}""")
                }
                else -> {
                    val result = request.messages.last()
                    assertEquals(AiMessage.Role.Tool, result.role)
                    assertTrue(result.content.contains("周末去海边看日出"), result.content)
                    answer("这句让人想起你们说好的周末去海边看日出[1]，也是先停一停。")
                }
            }
        }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, _, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "周末去海边看日出吧"))
            val book = aqi.addBook(room)
            val jobId = aqi.explain(room, book)
            ctx.jobs.drain()

            assertEquals(2, gateway.requests.size)
            val note = aqi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().highlights.single { it.id == jobId }.note
            assertEquals("这句让人想起你们说好的周末去海边看日出，也是先停一停。", note)
            val job = aqi.get("/api/v1/rooms/$room/ai/jobs/$jobId").body<AiJob>()
            assertEquals(AiJobStatus.Done, job.status)
            assertEquals(20, job.inputTokens)
            assertEquals(10, job.outputTokens)
        }
    }

    @Test fun `阅读里请 AI 查资料守「AI 能看什么」：有人关掉聊天，谁问都查不到聊天；关掉 AI_TOOLS 就不带工具`() {
        var toolResult = ""
        val gateway = ScriptedGateway { round, request ->
            when (round) {
                0 -> {
                    assertFalse(request.tools.any { it.name == "read_chat" }, "小迟关掉了聊天")
                    lookup("search", """{"query":"海边"}""")
                }
                else -> {
                    toolResult = request.messages.last().content
                    answer("没查到相关的，这段本身是说先停一停。")
                }
            }
        }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            aqi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "周末去海边看日出吧"))
            chi.patch("/api/v1/me", UpdateMeRequest(aiPrefs = Patch.of(AiPrefs(chat = false).toJson())))
            aqi.explain(room, aqi.addBook(room))
            ctx.jobs.drain()
            assertFalse(toolResult.contains("看日出"), toolResult)
        }

        val plain = ScriptedGateway { _, _ -> answer("这句是说，先停一停。") }
        val off = testContext(config = testConfig().let { it.copy(ai = it.ai.copy(tools = false)) }, clock = clock, aiGateway = plain)
        TestDatabase.reset()
        serverTest(off) { client ->
            val (aqi, _, room) = Api(client).pair()
            aqi.explain(room, aqi.addBook(room))
            off.jobs.drain()
            val request = plain.requests.single()
            assertTrue(request.tools.isEmpty())
            assertFalse(request.system.contains("用工具"), request.system)
        }
    }

    @Test fun `写作助手起草时能查房间资料（不带编号），也守「AI 能看什么」；润色不查`() {
        var toolResult = ""
        val gateway = ScriptedGateway { round, request ->
            when {
                request.tools.isEmpty() -> answer("周六一早，我们出发去东山岛。")
                round == 0 -> {
                    assertTrue(request.system.contains("可以用工具去房间里查"), request.system)
                    lookup("search", """{"query":"三脚架"}""")
                }
                else -> {
                    toolResult = request.messages.last().content
                    answer("# 东山岛的周末\n\n记得带上三脚架[1]。")
                }
            }
        }
        val ctx = testContext(clock = clock, aiGateway = gateway)
        serverTest(ctx) { client ->
            val (aqi, chi, room) = Api(client).pair()
            // 灵感是 24 号记的，起草的时间范围是月初，事先备的资料里没有，要 AI 自己查
            aqi.post("/api/v1/rooms/$room/ideas", CreateIdeaRequest(UuidV7.generate(), "下次带三脚架拍日落"))
            val draft = UuidV7.generate()
            aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(draft, WriteAssistMode.Draft, genre = DraftGenre.Travel,
                rangeStart = LocalDate.parse("2026-09-01"), rangeEnd = LocalDate.parse("2026-09-05")))
            ctx.jobs.drain()
            assertTrue(gateway.requests.first().messages.single().content.contains("这段时间没有记下什么"))
            assertTrue(toolResult.contains("下次带三脚架拍日落"), toolResult)
            assertEquals("# 东山岛的周末\n\n记得带上三脚架。", aqi.get("/api/v1/rooms/$room/ai/jobs/$draft").body<AiJob>().resultText)

            // 小迟关掉灵感：谁起草都查不到
            chi.patch("/api/v1/me", UpdateMeRequest(aiPrefs = Patch.of(AiPrefs(ideas = false).toJson())))
            gateway.requests.clear()
            toolResult = ""
            aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(UuidV7.generate(), WriteAssistMode.Draft, genre = DraftGenre.Travel,
                rangeStart = LocalDate.parse("2026-09-01"), rangeEnd = LocalDate.parse("2026-09-05")))
            ctx.jobs.drain()
            assertFalse(gateway.requests.first().tools.any { it.name == "ideas" })
            assertEquals(2, gateway.requests.size)
            assertFalse(toolResult.contains("拍日落"), toolResult)

            // 润色只看选中的文字，不带工具
            gateway.requests.clear()
            aqi.post("/api/v1/rooms/$room/ai/write-assist", AiWriteRequest(UuidV7.generate(), WriteAssistMode.Polish, "周六早上八点我们出发去东山岛"))
            ctx.jobs.drain()
            assertTrue(gateway.requests.single().tools.isEmpty())
        }
    }
}
