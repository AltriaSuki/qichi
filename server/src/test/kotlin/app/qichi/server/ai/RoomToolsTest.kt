package app.qichi.server.ai

import app.qichi.server.Api
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.ai.tools.RoomTools
import app.qichi.server.ai.tools.SourceBook
import app.qichi.server.db.tx
import app.qichi.server.serverTest
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.Book
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.Plan
import app.qichi.shared.api.QnaToday
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P11-02：问 AI 用的只读查询工具——只限本房间、删除撤回的不给、没揭晓的回答和私有摘录不给、关掉的类别不给、结果有长度上限。 */
class RoomToolsTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    private val zone = ZoneId.of("Asia/Shanghai")

    private class Room(val id: UUID, val names: Map<UUID, String>)

    private suspend fun room(aqi: Session, chi: Session, id: UUID) = Room(id, mapOf(aqi.userId() to "阿栖", chi.userId() to "小迟"))

    private fun Room.tools(prefs: AiPrefs = AiPrefs(), book: SourceBook = SourceBook()) = RoomTools(id, Instant.now(), zone, names, prefs, book)

    private suspend fun RoomTools.call(name: String, args: String = "{}"): String = TestDatabase.database.tx { run(AiToolCall("c", name, args)) }

    private suspend fun Session.say(room: UUID, text: String): Message =
        post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", text)).body<Message>()

    private val today: LocalDate get() = LocalDate.now(zone)

    @Test fun `只查得到本房间的：别的房间的聊天和灵感搜不到`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, roomId) = api.pair()
        val other = api.outsider(aqi)
        val otherRoom = other.createRoom("别人的房间").room.id
        other.say(otherRoom, "东山岛的民宿很好")
        other.post("/api/v1/rooms/$otherRoom/ideas", CreateIdeaRequest(UuidV7.generate(), "去东山岛看海"))
        aqi.say(roomId, "周末去海边吧")

        val tools = room(aqi, chi, roomId).tools()
        val found = tools.call("search", """{"query":"东山岛"}""")
        assertFalse(found.contains("民宿"), found)
        assertFalse(found.contains("看海"), found)
        val chat = tools.call("read_chat")
        assertTrue(chat.contains("周末去海边吧"), chat)
        assertFalse(chat.contains("民宿"), chat)
    }

    @Test fun `撤回的消息和删除的灵感查不到`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        val secret = aqi.say(roomId, "这是一条要撤回的民宿消息")
        aqi.post("/api/v1/rooms/$roomId/messages/${secret.id}/retract")
        aqi.say(roomId, "民宿订好了")
        val idea = aqi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "民宿旁边的小面馆")).body<app.qichi.shared.api.Idea>()
        chi.delete("/api/v1/rooms/$roomId/ideas/${idea.id}")

        val tools = room(aqi, chi, roomId).tools()
        val found = tools.call("search", """{"query":"民宿"}""")
        assertTrue(found.contains("民宿订好了"), found)
        assertFalse(found.contains("要撤回"), found)
        assertFalse(found.contains("小面馆"), found)
        assertFalse(tools.call("read_chat").contains("要撤回"))
        assertFalse(tools.call("ideas").contains("小面馆"))
    }

    @Test fun `没揭晓的问答回答查不到，两个人都确认后查得到`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        val path = "/api/v1/rooms/$roomId"
        val round = aqi.get("$path/qna/today").body<QnaToday>().round
        aqi.put("$path/qna/rounds/${round.id}/answer", WriteAnswerRequest(UuidV7.generate(), "想去山里住一晚"))
        aqi.post("$path/qna/rounds/${round.id}/confirm")

        val r = room(aqi, chi, roomId)
        val before = r.tools().call("qna")
        assertTrue(before.contains("还没揭晓"), before)
        assertFalse(before.contains("山里"), before)
        assertTrue(r.tools().call("search", """{"query":"山里"}""").startsWith("没有找到"))

        chi.put("$path/qna/rounds/${round.id}/answer", WriteAnswerRequest(UuidV7.generate(), "想一起做饭"))
        chi.post("$path/qna/rounds/${round.id}/confirm")
        val after = r.tools().call("qna")
        assertTrue(after.contains("阿栖：想去山里住一晚"), after)
        assertTrue(after.contains("小迟：想一起做饭"), after)
    }

    @Test fun `关掉的类别：不提供工具、硬调也不给、搜索跳过`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        aqi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "去海边看日出"))
        aqi.say(roomId, "海边那家民宿还有房")

        val tools = room(aqi, chi, roomId).tools(AiPrefs(ideas = false))
        assertTrue(tools.definitions.none { it.name == "ideas" })
        assertTrue(tools.definitions.any { it.name == "read_chat" })
        assertTrue(tools.call("ideas").startsWith("没有这个工具"))
        val found = tools.call("search", """{"query":"海边"}""")
        assertTrue(found.contains("民宿"), found)
        assertFalse(found.contains("日出"), found)
        // 搜索能选的类别里也没有灵感
        val search = tools.definitions.single { it.name == "search" }
        assertFalse(search.parameters.toString().contains("\"ideas\""))

        val noChat = room(aqi, chi, roomId).tools(AiPrefs(chat = false))
        assertTrue(noChat.definitions.none { it.name == "read_chat" })
        assertFalse(noChat.call("search", """{"query":"海边"}""").contains("民宿"))
    }

    @Test fun `结果太长时截断，并告诉 AI 还有多少条`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        repeat(60) { i -> aqi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "灵感 $i：" + "想去的地方".repeat(30))) }
        val out = room(aqi, chi, roomId).tools().call("ideas", """{"limit":60}""")
        assertTrue(out.length <= 4100, "长度 ${out.length}")
        assertTrue(Regex("还有 \\d+ 条没列出").containsMatchIn(out), out.takeLast(200))
    }

    @Test fun `编号：列表里的编号能看详情；编号类别不对时告诉 AI；事先备料和工具共用一套编号`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        val plan = aqi.post("/api/v1/rooms/$roomId/plans", CreatePlanRequest(UuidV7.generate(), "秋天去海边", aqi.userId(), nextStep = "选地方")).body<Plan>()
        aqi.post("/api/v1/rooms/$roomId/ideas", CreateIdeaRequest(UuidV7.generate(), "带上相机"))

        val book = SourceBook()
        val tools = room(aqi, chi, roomId).tools(book = book)
        val list = tools.call("plans")
        val n = Regex("\\[(\\d+)] 计划 · 秋天去海边").find(list)!!.groupValues[1].toInt()
        assertEquals(plan.id, book[n]!!.id)
        assertEquals("plan", book[n]!!.type)
        val detail = tools.call("plans", """{"ref":$n}""")
        assertTrue(detail.contains("[$n] 计划 · 秋天去海边"), detail)
        assertTrue(detail.contains("下一步：选地方"), detail)

        val ideas = tools.call("ideas")
        val m = Regex("\\[(\\d+)] 灵感").find(ideas)!!.groupValues[1].toInt()
        assertTrue(m != n)
        assertTrue(tools.call("plans", """{"ref":$m}""").startsWith("参数不对：[$m] 是灵感"))
        assertTrue(tools.call("plans", """{"ref":99}""").startsWith("参数不对：没有编号 [99]"))
        assertTrue(tools.call("events", """{"from":"下周"}""").startsWith("参数不对"))
    }

    @Test fun `日期段：日程和聊天按房间时区的日子算`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        aqi.say(roomId, "今天说的话")
        val tools = room(aqi, chi, roomId).tools()
        val todayChat = tools.call("read_chat", """{"from":"$today","to":"$today"}""")
        assertTrue(todayChat.contains("今天说的话"), todayChat)
        val yesterday = today.minusDays(1)
        assertEquals("这段时间没有聊天", tools.call("read_chat", """{"from":"$yesterday","to":"$yesterday"}"""))
        assertTrue(tools.call("events", """{"from":"$today","to":"${today.plusDays(500)}"}""").contains("最多查"))
    }

    @Test fun `文稿：只读已保存的最新版，长文分段接着读`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        val docs = "/api/v1/rooms/$roomId/documents"
        val doc = aqi.post(docs, CreateDocumentRequest(UuidV7.generate(), "给明年秋天的信")).body<Document>()
        aqi.post("$docs/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, "第一版的开头"))
        val long = "开头。" + "我们慢慢走。".repeat(800) + "结尾在这里。"
        chi.post("$docs/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 1, long))
        aqi.post(docs, CreateDocumentRequest(UuidV7.generate(), "没保存过的"))

        val book = SourceBook()
        val tools = room(aqi, chi, roomId).tools(book = book)
        val list = tools.call("documents")
        assertTrue(list.contains("《没保存过的》 · 还没保存过"), list)
        val n = Regex("\\[(\\d+)] 文稿《给明年秋天的信》").find(list)!!.groupValues[1].toInt()
        val first = tools.call("documents", """{"ref":$n}""")
        assertFalse(first.contains("第一版的开头"), first)
        assertTrue(first.contains("开头。我们慢慢走。"))
        assertFalse(first.contains("结尾在这里"))
        val offset = Regex("offset=(\\d+)").find(first)!!.groupValues[1]
        val rest = tools.call("documents", """{"ref":$n,"offset":$offset}""")
        assertTrue(rest.contains("结尾在这里。"), rest.takeLast(100))
    }

    private fun epub(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container/>".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun `阅读：只给公开的摘录，私下的划线查不到`() = serverTest { client ->
        val (aqi, chi, roomId) = Api(client).pair()
        val file = aqi.upload(roomId, epub(), fileName = "book.epub", kind = "epub", contentType = "application/epub+zip").body<FileMeta>()
        val b = aqi.post("/api/v1/rooms/$roomId/books", CreateBookRequest(UuidV7.generate(), file.id, "海边的旅店", "某某")).body<Book>()
        val path = "/api/v1/rooms/$roomId/books/${b.id}/highlights"
        aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Highlight, "{}", "不必急着去哪里", "私下想的"))
        aqi.post(path, CreateHighlightRequest(UuidV7.generate(), HighlightKind.Excerpt, "{}", "先在这里坐一会儿", "我也喜欢这一句", shared = true))

        val book = SourceBook()
        val tools = room(aqi, chi, roomId).tools(book = book)
        val shelf = tools.call("reading")
        val n = Regex("\\[(\\d+)] 书《海边的旅店》").find(shelf)!!.groupValues[1].toInt()
        val detail = tools.call("reading", """{"ref":$n}""")
        assertTrue(detail.contains("先在这里坐一会儿"), detail)
        assertFalse(detail.contains("不必急着"), detail)
        assertFalse(detail.contains("私下想的"), detail)
        assertTrue(tools.call("search", """{"query":"不必急着"}""").startsWith("没有找到"))
    }
}
