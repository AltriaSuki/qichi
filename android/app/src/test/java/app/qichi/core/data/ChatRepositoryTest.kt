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
import app.qichi.core.ui.chatDay
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.EntityType
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
    fun `聊天日期分隔的说法`() {
        val today = LocalDate.of(2026, 9, 21)
        assertEquals("今天" to false, chatDay(today, today))
        assertEquals("昨天" to false, chatDay(today.minusDays(1), today))
        assertEquals("9 · 18  周五" to true, chatDay(LocalDate.of(2026, 9, 18), today))
        assertEquals("2025 · 12 · 31" to true, chatDay(LocalDate.of(2025, 12, 31), today))
    }
}
