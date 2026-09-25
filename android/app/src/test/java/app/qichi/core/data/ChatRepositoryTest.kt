package app.qichi.core.data

import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.ChatHistoryRow
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.core.sync.bodyText
import app.qichi.core.ui.chatDay
import app.qichi.shared.api.AcceptAiActionRequest
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.AiActionDraft
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.model.AiActionKind
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChatRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var server: FakeServer
    private lateinit var chat: ChatRepository
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        server = FakeServer()
        val api = SyncFixtures.api(server.engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        chat = ChatRepository(db, store, api, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    private fun serverMessage(seq: Long, body: String = "第 $seq 条", author: UUID = partner) =
        SyncFixtures.pendingMessage(body).copy(seq = seq, createdSeq = seq, authorId = author)

    @Test
    fun `发消息：本机先出现（待发送），回复摘要按规则先算好，发件箱里是发送请求`() = runTest {
        val original = serverMessage(7, "周六早上出发怎么样？海边那家民宿还有房。")
        store.applyServer(original)

        val sent = chat.sendText(roomId, "  好呀，我把外套带上。 ", replyTo = original)
        val local = store.get<Message>(EntityType.Message, sent.id)!!
        assertTrue(local.isPending)
        assertEquals("好呀，我把外套带上。", local.value.body)
        assertEquals(me, local.value.authorId)
        assertEquals(partner, local.value.replyAuthorId)
        assertEquals(original.body, local.value.replyExcerpt)

        val outbox = db.outbox().all().single()
        assertEquals("rooms/$roomId/messages", outbox.path)
        val request = QichiJson.decodeFromString(SendMessageRequest.serializer(), outbox.bodyJson!!)
        assertEquals(sent.id, request.id)
        assertEquals(original.id, request.replyToId)

        // 最新一条就是它（待发送的排在最下面）
        assertEquals(sent.id, chat.observeNewest(roomId).first()!!.value.id)
    }

    @Test
    fun `往上翻历史：从连续历史的最早一条往前取，取到头就停`() = runTest {
        db.chatHistory().upsert(ChatHistoryRow(roomId.toString(), 101))
        val asked = mutableListOf<String>()
        server.custom = { request ->
            asked += request.url.encodedPath + "?" + request.url.encodedQuery
            val before = request.url.parameters["beforeSeq"]!!.toLong()
            val messages = ((before - 50).coerceAtLeast(1) until before).reversed().map { serverMessage(it) }
            respond(QichiJson.encodeToString(MessagePage.serializer(), MessagePage(messages, hasMore = messages.last().createdSeq > 1)), HttpStatusCode.OK, json)
        }

        assertTrue(chat.loadOlder(roomId))
        assertEquals(51, db.chatHistory().floor(roomId.toString()))
        assertFalse(chat.loadOlder(roomId), "第二页取到第 1 条，没有更早的了")
        assertEquals(0, db.chatHistory().floor(roomId.toString()))
        assertFalse(chat.loadOlder(roomId))
        assertEquals(2, asked.size, "到头之后不再请求")
        assertTrue(asked[0].contains("beforeSeq=101"))
        assertTrue(asked[1].contains("beforeSeq=51"))
    }

    @Test
    fun `比连续历史更早、单独同步下来的旧消息先不显示，免得中间断档`() = runTest {
        (100L..110L).forEach { store.applyServer(serverMessage(it)) }
        store.applyServer(serverMessage(5, "从回收站恢复的旧消息"))
        db.chatHistory().upsert(ChatHistoryRow(roomId.toString(), 100))

        val page = db.entities().messagesPaging(roomId.toString())
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false))
        val bodies = (page as PagingSource.LoadResult.Page).data.map { LocalStore.toLocal<Message>(it).value.body }
        assertEquals("第 110 条", bodies.first())
        assertEquals("第 100 条", bodies.last())
        assertFalse(bodies.contains("从回收站恢复的旧消息"))
    }

    @Test
    fun `跳到本机还没有的原消息：一页页往上取，直到找到；位置是比它新的消息数`() = runTest {
        val local = (500L..549L).associateWith { serverMessage(it) }
        local.values.forEach { store.applyServer(it) }
        db.chatHistory().upsert(ChatHistoryRow(roomId.toString(), 500))
        val all = (1L..499L).associateWith { serverMessage(it) }
        var requests = 0
        server.custom = { request ->
            requests++
            val before = request.url.parameters["beforeSeq"]!!.toLong()
            val page = ((before - 50).coerceAtLeast(1) until before).reversed().map { all.getValue(it) }
            respond(QichiJson.encodeToString(MessagePage.serializer(), MessagePage(page, hasMore = page.last().createdSeq > 1)), HttpStatusCode.OK, json)
        }
        val target = all.getValue(30)

        val position = chat.positionOf(roomId, target.id)
        assertEquals(ChatRepository.Position.Found(549 - 30), position)
        assertEquals(10, requests, "从 500 往前到 30，每页 50 条")

        // 已在本机：不再请求
        assertEquals(ChatRepository.Position.Found(549 - 520), chat.positionOf(roomId, local.getValue(520).id))
        assertEquals(10, requests)

        // 在回收站里
        store.applyServer(all.getValue(40).copy(deletedAt = SyncFixtures.t0, deletedBy = partner, seq = 600))
        assertEquals(ChatRepository.Position.Deleted, chat.positionOf(roomId, all.getValue(40).id))
        // 翻到最早也没有
        assertEquals(ChatRepository.Position.Missing, chat.positionOf(roomId, UUID.randomUUID()))
    }

    @Test
    fun `未读：只算对方的、在我的位置之后的；推进只进不退，连续推进只发最后一次`() = runTest {
        (1L..5L).forEach { store.applyServer(serverMessage(it, author = if (it == 3L) me else partner)) }
        assertEquals(4, chat.observeUnread(roomId).first(), "我自己发的第 3 条不算")

        chat.markRead(roomId, 2)
        assertEquals(0L + 2, chat.observeLastRead(roomId).first())
        assertEquals(2, chat.observeUnread(roomId).first())
        chat.markRead(roomId, 1)
        assertEquals(2L, chat.observeLastRead(roomId).first(), "不会退回去")
        chat.markRead(roomId, 5)
        assertEquals(0, chat.observeUnread(roomId).first())

        val ops = db.outbox().all()
        assertEquals(1, ops.size, "只保留最后一次推进")
        assertEquals(5L, QichiJson.decodeFromString(UpdateReadMarkerRequest.serializer(), ops.single().bodyJson!!).lastReadSeq)
    }

    @Test
    fun `第一次推进用的临时行，在服务端响应回来后换成服务端那一行`() = runTest {
        store.applyServer(serverMessage(9))
        chat.markRead(roomId, 9)
        val placeholder = db.entities().readMarkers(roomId.toString(), me.toString()).single()

        val server = ReadMarker(UUID.randomUUID(), roomId, 12, SyncFixtures.t0, SyncFixtures.t0, null, null, me, 9)
        store.applyReadMarker(server)
        val rows = db.entities().readMarkers(roomId.toString(), me.toString())
        assertEquals(listOf(server.id.toString()), rows.map { it.id })
        assertTrue(rows.single().id != placeholder.id)
        assertEquals(9L, chat.observeLastRead(roomId).first())
    }

    @Test
    fun `问 AI：直接请求（不进发件箱），回答同步下来后能察觉`() = runTest {
        val jobId = UUID(0, 1)
        var seen: Pair<String, String>? = null
        server.custom = { request ->
            seen = request.url.encodedPath to request.bodyText()
            respond(QichiJson.encodeToString(AiJobAccepted.serializer(), AiJobAccepted(jobId, AiJobStatus.Queued)), HttpStatusCode.Accepted, json)
        }
        assertEquals(AiJobStatus.Queued, chat.askAi(roomId, jobId, "周六去哪片海？").status)
        assertEquals("/api/v1/rooms/$roomId/ai/chat", seen!!.first)
        assertEquals(AiChatRequest(jobId, "周六去哪片海？"), QichiJson.decodeFromString(AiChatRequest.serializer(), seen!!.second))
        assertTrue(db.outbox().all().isEmpty(), "AI 请求不进离线发件箱")

        assertEquals(false, chat.observeHasMessage(jobId).first())
        store.applyServer(serverMessage(3).copy(id = jobId, kind = MessageKind.Ai, authorId = null, aiPrompt = "周六去哪片海？"))
        assertEquals(true, chat.observeHasMessage(jobId).first())
    }

    @Test
    fun `停下 AI：直接请求停下接口（不进发件箱）`() = runTest {
        val jobId = UUID(0, 2)
        var seen: String? = null
        server.custom = { request ->
            seen = request.method.value + " " + request.url.encodedPath
            respond(QichiJson.encodeToString(AiJobAccepted.serializer(), AiJobAccepted(jobId, AiJobStatus.Running)), HttpStatusCode.OK, json)
        }
        assertEquals(AiJobStatus.Running, chat.stopAi(roomId, jobId).status)
        assertEquals("POST /api/v1/rooms/$roomId/ai/jobs/$jobId/stop", seen)
        assertTrue(db.outbox().all().isEmpty())
    }

    @Test
    fun `发照片带说明：说明整理后写进正文，发送请求里也带上；文件不带说明`() = runTest {
        val now = java.time.Instant.parse("2026-09-24T02:00:00Z")
        val photo = FileMeta(UUID(0, 3), roomId, FileKind.Image, "sea.jpg", "image/jpeg", 10, "0".repeat(64), 400, 300, me, now)
        val sent = chat.sendAttachment(roomId, photo, caption = "  那家民宿的\n窗外 ")
        assertEquals("那家民宿的 窗外", store.get<Message>(EntityType.Message, sent.id)!!.value.body)
        val request = QichiJson.decodeFromString(SendMessageRequest.serializer(), db.outbox().all().single().bodyJson!!)
        assertEquals("那家民宿的 窗外", request.body)

        val doc = photo.copy(id = UUID(0, 4), kind = FileKind.File, fileName = "a.pdf", mimeType = "application/pdf")
        assertEquals("", chat.sendAttachment(roomId, doc, caption = "不该有").body)
    }

    @Test
    fun `AI 提议：好、不用都先改本机再走发件箱；好带上客户端生成的 id；处理过的不重复发`() = runTest {
        val t0 = java.time.Instant.parse("2026-09-24T02:00:00Z")
        val jobId = UUID.randomUUID()
        fun action(position: Int, title: String) = AiAction(
            UUID.randomUUID(), roomId, 10L + position, t0, t0, null, null, jobId, position, AiActionKind.Todo,
            AiActionDraft(title), AiActionStatus.Proposed, null, null, partner,
        )
        val coat = action(0, "带外套")
        val tickets = action(1, "买票")
        store.applyServer(tickets)
        store.applyServer(coat)
        assertEquals(listOf("带外套", "买票"), chat.observeAiActions(roomId).first()[jobId]!!.map { it.draft.title }, "按在回答里的顺序")

        server.online = false
        chat.acceptAiAction(coat)
        chat.dismissAiAction(tickets)
        val local = chat.observeAiActions(roomId).first()[jobId]!!
        assertEquals(listOf(AiActionStatus.Accepted, AiActionStatus.Dismissed), local.map { it.status })
        val ops = db.outbox().all()
        assertEquals(listOf("/api/v1/rooms/$roomId/ai-actions/${coat.id}/accept", "/api/v1/rooms/$roomId/ai-actions/${tickets.id}/dismiss").map { it.removePrefix("/api/v1/") },
            ops.map { it.path })
        assertEquals(local[0].resultId, QichiJson.decodeFromString(AcceptAiActionRequest.serializer(), ops[0].bodyJson!!).resultId)

        // 已经处理过的：再点不会多一条发件箱
        chat.acceptAiAction(local[0])
        chat.dismissAiAction(local[1])
        assertEquals(2, db.outbox().all().size)
    }

    @Test
    fun `聊天日期分隔的说法`() {
        val today = LocalDate.of(2026, 9, 21)
        assertEquals("今天" to false, chatDay(today, today))
        assertEquals("昨天" to false, chatDay(today.minusDays(1), today))
        assertEquals("09.18  周五" to true, chatDay(LocalDate.of(2026, 9, 18), today))
        assertEquals("2025.12.31" to true, chatDay(LocalDate.of(2025, 12, 31), today))
    }
}
