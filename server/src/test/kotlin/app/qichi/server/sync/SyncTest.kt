package app.qichi.server.sync

import app.qichi.server.AppContext
import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.db.ChangeLog
import app.qichi.server.db.Messages
import app.qichi.server.db.ReadMarkers
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Me
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.api.WsEvent
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class SyncTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    /** 测试夹具：绕过接口，直接用 RoomWriter 写一条待办（待办接口在 P2-02）。 */
    private suspend fun AppContext.writeTodo(roomId: UUID, userId: UUID, id: UUID, title: String, deleted: Boolean = false) {
        database.tx {
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Todo, id, userId, now)
            val updated = Todos.update({ Todos.id eq id }) {
                it[Todos.title] = title
                it[Todos.seq] = seq
                it[updatedAt] = now
                it[deletedAt] = if (deleted) now else null
                it[deletedBy] = if (deleted) userId else null
            }
            if (updated == 0) {
                Todos.insert {
                    it[Todos.id] = id
                    it[Todos.roomId] = roomId
                    it[Todos.title] = title
                    it[createdBy] = userId
                    it[Todos.seq] = seq
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[deletedAt] = if (deleted) now else null
                    it[deletedBy] = if (deleted) userId else null
                }
            }
        }
    }

    private suspend fun app.qichi.server.Session.userId(): UUID = get("/api/v1/me").body<Me>().user.id

    @Test
    fun `写入 3 次同一待办，sync 只返回 1 条且为最新`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (owner, member, roomId) = Api(client).pair()
            val ownerId = owner.userId()
            val todoId = UuidV7.generate()
            ctx.writeTodo(roomId, ownerId, todoId, "买菜")
            ctx.writeTodo(roomId, ownerId, todoId, "买菜和水果")
            ctx.writeTodo(roomId, ownerId, todoId, "买菜、水果和花")

            val sync = member.get("/api/v1/rooms/$roomId/sync?since=0").body<SyncResponse>()
            val todoChanges = sync.changes.filter { it.type == EntityType.Todo }
            assertEquals(1, todoChanges.size)
            val todo = EntityCodec.decode(EntityType.Todo, todoChanges.single().data!!) as Todo
            assertEquals("买菜、水果和花", todo.title)
            assertEquals(todoChanges.single().seq, todo.seq)
            assertEquals(sync.toSeq, todo.seq)
            assertFalse(sync.hasMore)
            // 房间与两个成员也在里面，按 seq 升序
            assertEquals(listOf(EntityType.Room, EntityType.Member, EntityType.Member, EntityType.Todo), sync.changes.map { it.type })
            assertEquals(sync.changes.map { it.seq }.sorted(), sync.changes.map { it.seq })
        }
    }

    @Test
    fun `limit 分页的 hasMore 与 toSeq 正确，逐页拉完得到全部实体`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (owner, member, roomId) = Api(client).pair()
            val ownerId = owner.userId()
            val ids = List(5) { UuidV7.generate() }
            ids.forEachIndexed { i, id -> ctx.writeTodo(roomId, ownerId, id, "待办 $i") }
            // 房间 1 + 成员 2 + 待办 5 = 8 条变化

            var since = 0L
            val seen = mutableListOf<UUID>()
            var pages = 0
            do {
                val page = member.get("/api/v1/rooms/$roomId/sync?since=$since&limit=3").body<SyncResponse>()
                assertEquals(since, page.fromSeq)
                assertTrue(page.changes.all { it.seq in (since + 1)..page.toSeq })
                seen += page.changes.map { it.id }
                since = page.toSeq
                pages++
            } while (page.hasMore)
            assertEquals(3, pages)
            assertEquals(8L, since)
            assertTrue(seen.containsAll(ids))

            val empty = member.get("/api/v1/rooms/$roomId/sync?since=8").body<SyncResponse>()
            assertEquals(SyncResponse(8, 8, false, emptyList()), empty)
        }
    }

    @Test
    fun `对方的未读位置不下发，但 toSeq 照常前进`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (owner, member, roomId) = Api(client).pair()
            val ownerId = owner.userId()
            ctx.database.tx {
                val now = ctx.clock.instant()
                val id = UuidV7.generate()
                val seq = ctx.writer.change(this, roomId, EntityType.ReadMarker, id, ownerId, now)
                ReadMarkers.insert {
                    it[ReadMarkers.id] = id
                    it[ReadMarkers.roomId] = roomId
                    it[userId] = ownerId
                    it[lastReadSeq] = 3
                    it[ReadMarkers.seq] = seq
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            val forMember = member.get("/api/v1/rooms/$roomId/sync?since=3").body<SyncResponse>()
            assertTrue(forMember.changes.isEmpty())
            assertEquals(4, forMember.toSeq)
            val forOwner = owner.get("/api/v1/rooms/$roomId/sync?since=3").body<SyncResponse>()
            assertEquals(EntityType.ReadMarker, forOwner.changes.single().type)

            assertNull(member.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().readMarker)
            assertEquals(3, owner.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>().readMarker!!.lastReadSeq)
        }
    }

    @Test
    fun `彻底删除的实体以 op=delete 出现，data 为 null`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (owner, member, roomId) = Api(client).pair()
            val ownerId = owner.userId()
            val todoId = UuidV7.generate()
            ctx.writeTodo(roomId, ownerId, todoId, "临时")
            ctx.database.tx {
                val seq = ctx.writer.change(this, roomId, EntityType.Todo, todoId, ownerId, ctx.clock.instant(), ChangeOp.Delete)
                Todos.deleteWhere { Todos.id eq todoId }
                assertTrue(seq > 0)
            }
            val change = member.get("/api/v1/rooms/$roomId/sync?since=3").body<SyncResponse>().changes.single()
            assertEquals(ChangeOp.Delete, change.op)
            assertNull(change.data)
            assertEquals(ChangeOp.Delete.wireName, "delete")
        }
    }

    @Test
    fun `bootstrap 返回快照：包括已软删除的实体，最近 50 条消息`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (owner, member, roomId) = Api(client).pair()
            val ownerId = owner.userId()
            ctx.writeTodo(roomId, ownerId, UuidV7.generate(), "进行中")
            ctx.writeTodo(roomId, ownerId, UuidV7.generate(), "已删除", deleted = true)
            ctx.database.tx {
                repeat(55) { i ->
                    val now = ctx.clock.instant()
                    val id = UuidV7.generate()
                    val seq = ctx.writer.change(this, roomId, EntityType.Message, id, ownerId, now)
                    Messages.insert {
                        it[Messages.id] = id
                        it[Messages.roomId] = roomId
                        it[authorId] = ownerId
                        it[kind] = "text"
                        it[body] = "第 $i 条"
                        it[createdSeq] = seq
                        it[Messages.seq] = seq
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
            val boot = member.get("/api/v1/rooms/$roomId/bootstrap").body<Bootstrap>()
            assertEquals(2, boot.members.size)
            assertEquals(2, boot.todos.size)
            assertEquals(1, boot.todos.count { it.deletedAt != null })
            assertEquals(50, boot.messages.size)
            assertEquals("第 54 条", boot.messages.first().body)
            assertTrue(boot.hasMoreMessages)
            assertEquals(ChangeLogCount.of(roomId), boot.lastSeq)
        }
    }

    @Test
    fun `非成员 bootstrap 与 sync 都是 404；参数不合法 400`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val other = owner.createRoom("另一个").room.id
        member.get("/api/v1/rooms/$other/bootstrap").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        member.get("/api/v1/rooms/$other/sync?since=0").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        member.get("/api/v1/rooms/$roomId/sync").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        member.get("/api/v1/rooms/$roomId/sync?since=0&limit=5000").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `另一个成员的 WebSocket 能收到 hello 与 changed`() = serverTest { client ->
        val (owner, member, roomId) = Api(client).pair()
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/api/v1/ws", request = { bearerAuth(member.tokens.accessToken) }) {
            val hello = QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            hello as WsEvent.Hello
            assertEquals(roomId, hello.rooms.single().roomId)
            val before = hello.rooms.single().lastSeq

            owner.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(name = Patch.of("新名字")))
            val changed = withTimeout(5_000) {
                QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            }
            assertEquals(WsEvent.Changed(roomId, before + 1), changed)
        }
    }

    @Test
    fun `WebSocket 不收别的房间的变化，未登录无法连接`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val otherRoom = owner.createRoom("另一个").room.id
        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket("/api/v1/ws", request = { bearerAuth(member.tokens.accessToken) }) {
            incoming.receive() // hello
            owner.patch("/api/v1/rooms/$otherRoom", UpdateRoomRequest(name = Patch.of("x")))
            owner.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(name = Patch.of("y")))
            val changed = QichiJson.decodeFromString(WsEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            assertEquals(roomId, (changed as WsEvent.Changed).roomId)
        }
        val unauthorized = runCatching {
            wsClient.webSocket("/api/v1/ws") { incoming.receive() }
        }
        assertTrue(unauthorized.isFailure)
    }
}

private object ChangeLogCount {
    suspend fun of(roomId: UUID): Long = TestDatabase.database.tx {
        ChangeLog.select(ChangeLog.seq).where { ChangeLog.roomId eq roomId }.count()
    }
}
