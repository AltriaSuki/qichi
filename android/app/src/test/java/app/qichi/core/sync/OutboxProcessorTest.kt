package app.qichi.core.sync

import app.qichi.core.database.OutboxState
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.pendingMessage
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncFixtures.todo
import app.qichi.shared.api.Message
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.api.UpdateTodoRequest
import app.qichi.shared.model.EntityType
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/05-sync-offline.md §5 要求的客户端测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OutboxProcessorTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var server: FakeServer
    private lateinit var processor: OutboxProcessor

    @Before
    fun setUp() {
        db = SyncFixtures.database()
        store = LocalStore(db)
        server = FakeServer()
        processor = OutboxProcessor(SyncFixtures.api(server.engine), db, store)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sendLocally(body: String): UUID {
        val message = pendingMessage(body)
        store.writeLocal(
            roomId,
            message,
            OutboxOp.post("rooms/$roomId/messages", SendMessageRequest(message.id, "text", body)),
        )
        return message.id
    }

    private val problemHeaders = headersOf(HttpHeaders.ContentType, "application/problem+json")

    @Test
    fun `断网写三条消息，恢复网络后按顺序各发一次（即使一次响应丢失也不重复）`() = runTest {
        val ids = listOf("周六早上出发怎么样？", "海边那家民宿还有房。", "那我今晚先把行李收好。").map { sendLocally(it) }
        assertTrue(ids.all { store.get<Message>(EntityType.Message, it)!!.isPending })

        // 离线：一条也发不出去，第一条记一次尝试
        server.online = false
        assertIs<OutboxProcessor.Result.Retry>(processor.drain())
        assertEquals(1, db.outbox().all().first().attempts)
        assertTrue(server.messages.isEmpty())

        // 联网，但第一条的响应在路上丢了：服务端其实已经收下
        server.online = true
        server.loseNextResponse = true
        assertIs<OutboxProcessor.Result.Retry>(processor.drain())
        assertEquals(1, server.messages.size)

        // 再发：第一条按 id 幂等返回已有的，后两条依次发出
        val result = processor.drain()
        assertEquals(OutboxProcessor.Result.Done(setOf(roomId)), result)
        assertEquals(ids, server.messages.keys.toList(), "服务端按顺序各收到一条")
        assertTrue(db.outbox().all().isEmpty())

        val local = ids.map { store.get<Message>(EntityType.Message, it)!! }
        assertTrue(local.all { it.syncState == SyncState.SYNCED })
        assertEquals(listOf(1L, 2L, 3L), local.map { it.value.createdSeq })
    }

    @Test
    fun `5xx 时退避重试，不丢操作`() = runTest {
        val id = sendLocally("你好")
        server.failNext500 = 1
        assertIs<OutboxProcessor.Result.Retry>(processor.drain())
        val row = db.outbox().all().single()
        assertEquals(1, row.attempts)
        assertEquals(OutboxState.PENDING, row.state)
        assertTrue(store.get<Message>(EntityType.Message, id)!!.isPending)

        assertIs<OutboxProcessor.Result.Done>(processor.drain())
        assertEquals(SyncState.SYNCED, store.get<Message>(EntityType.Message, id)!!.syncState)
    }

    @Test
    fun `4xx 标记失败，同一实体后面的操作一并失败，不阻塞其它实体`() = runTest {
        val a = todo("会被拒绝")
        val b = todo("正常")
        server.custom = { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/todos") && request.bodyText().contains(a.id.toString()) ->
                    respond("""{"type":"x","title":"标题太长","status":400,"code":"invalid_request"}""", HttpStatusCode.BadRequest, problemHeaders)
                path.endsWith("/todos") ->
                    respond(QichiJson.encodeToString(Todo.serializer(), b.copy(seq = 9)), HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> null
            }
        }
        store.writeLocal(roomId, a, OutboxOp.post("rooms/$roomId/todos", mapOf("id" to a.id.toString())))
        store.writeLocal(roomId, a.copy(title = "改了一下"), OutboxOp.patch("rooms/$roomId/todos/${a.id}", UpdateTodoRequest(title = Patch.of("改了一下"))))
        store.writeLocal(roomId, b, OutboxOp.post("rooms/$roomId/todos", mapOf("id" to b.id.toString())))

        assertEquals(OutboxProcessor.Result.Done(setOf(roomId)), processor.drain())
        assertEquals(SyncState.FAILED, store.get<Todo>(EntityType.Todo, a.id)!!.syncState)
        assertEquals(SyncState.SYNCED, store.get<Todo>(EntityType.Todo, b.id)!!.syncState)
        val remaining = db.outbox().all()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.entityId == a.id.toString() && it.state == OutboxState.FAILED })
        // A 的第二个操作（PATCH）没有发出去
        assertEquals(0, server.requests.count { it.startsWith("PATCH") })
        assertEquals(2, server.requests.count { it.startsWith("POST") })

        // 重试：放回队列
        store.retry(EntityType.Todo, a.id)
        assertEquals(SyncState.PENDING, store.get<Todo>(EntityType.Todo, a.id)!!.syncState)
        assertTrue(db.outbox().all().all { it.state == OutboxState.PENDING })

        // 放弃：服务端从来没有这个实体，本地删除
        store.abandon(EntityType.Todo, a.id)
        assertNull(store.get<Todo>(EntityType.Todo, a.id))
        assertTrue(db.outbox().all().isEmpty())
    }

    @Test
    fun `409 版本冲突时实体标记冲突，本地内容保留，继续发后面的`() = runTest {
        val existing = todo("原来的标题", seq = 5)
        store.applyServer(existing)
        server.custom = { request ->
            if (request.method.value == "PATCH") {
                respond("""{"type":"x","title":"已有更新的版本","status":409,"code":"conflict_version","latestVersion":8}""", HttpStatusCode.Conflict, problemHeaders)
            } else {
                null
            }
        }
        store.writeLocal(roomId, existing.copy(title = "我的修改"), OutboxOp.patch("rooms/$roomId/todos/${existing.id}", UpdateTodoRequest(title = Patch.of("我的修改"))))
        val message = sendLocally("冲突之后的消息")

        assertIs<OutboxProcessor.Result.Done>(processor.drain())
        val local = store.get<Todo>(EntityType.Todo, existing.id)!!
        assertEquals(SyncState.CONFLICT, local.syncState)
        assertEquals("我的修改", local.value.title)
        assertEquals(SyncState.SYNCED, store.get<Message>(EntityType.Message, message)!!.syncState)
        assertTrue(db.outbox().all().isEmpty())
    }

    @Test
    fun `放弃发送失败的修改时恢复成服务端的内容`() = runTest {
        val existing = todo("服务端的标题", seq = 3)
        store.applyServer(existing)
        store.writeLocal(roomId, existing.copy(title = "本机改的"), OutboxOp.patch("rooms/$roomId/todos/${existing.id}", UpdateTodoRequest(title = Patch.of("本机改的"))))
        store.markFailed("todo", existing.id.toString(), "失败")

        store.abandon(EntityType.Todo, existing.id)
        val local = store.get<Todo>(EntityType.Todo, existing.id)!!
        assertEquals("服务端的标题", local.value.title)
        assertEquals(SyncState.SYNCED, local.syncState)
    }

    @Test
    fun `推进已读位置多次只保留最后一次`() = runTest {
        val marker = app.qichi.shared.api.ReadMarker(
            id = UUID.randomUUID(), roomId = roomId, seq = 0, createdAt = SyncFixtures.t0, updatedAt = SyncFixtures.t0,
            deletedAt = null, deletedBy = null, userId = me, lastReadSeq = 0,
        )
        for (n in listOf(5L, 9L, 12L)) {
            store.writeLocal(
                roomId,
                marker.copy(lastReadSeq = n),
                OutboxOp.put("rooms/$roomId/read-marker", UpdateReadMarkerRequest(n), kind = OutboxOp.KIND_READ_MARKER),
                coalesce = true,
            )
        }
        val rows = db.outbox().all()
        assertEquals(1, rows.size)
        assertTrue(rows.single().bodyJson!!.contains("12"))
    }
}
