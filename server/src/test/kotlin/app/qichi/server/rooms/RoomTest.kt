package app.qichi.server.rooms

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AcceptInviteRequest
import app.qichi.shared.api.CreateRoomRequest
import app.qichi.shared.api.Invite
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Room
import app.qichi.shared.api.RoomDetail
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private fun changeLogSeqs(roomId: UUID): List<Long> =
        TestDatabase.database.dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT seq FROM change_log WHERE room_id = ? ORDER BY seq").use { st ->
                st.setObject(1, roomId)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    @Test
    fun `建房间：创建者是 owner，同 id 重复提交返回已有房间`() = serverTest { client ->
        val api = Api(client)
        val aqi = api.registerOk("aqi")
        val id = UuidV7.generate()
        val created = aqi.createRoom("两个人的屋檐", id)
        assertEquals("Asia/Shanghai", created.room.timezone)
        assertEquals(MemberRole.Owner, created.members.single().role)
        assertEquals(2, created.lastSeq)

        val again = aqi.post("/api/v1/rooms", CreateRoomRequest(id = id, name = "两个人的屋檐"))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(created.room.id, again.body<RoomDetail>().room.id)
        assertEquals(listOf(1L, 2L), changeLogSeqs(id))
    }

    @Test
    fun `邀请码：仅 owner，8 位去易混字符；用邀请码注册后两人都在房间里`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()

        val detail = member.get("/api/v1/rooms/$roomId").body<RoomDetail>()
        assertEquals(listOf("aqi", "xiaochi"), detail.members.map { it.username })
        assertEquals(listOf(MemberRole.Owner, MemberRole.Member), detail.members.map { it.role })

        member.post("/api/v1/rooms/$roomId/invites").assertProblem(HttpStatusCode.Forbidden, ProblemCode.Forbidden)
        owner.post("/api/v1/rooms/$roomId/invites").assertProblem(HttpStatusCode.Conflict, ProblemCode.RoomFull)
    }

    @Test
    fun `邀请码格式与有效期；生成新码后旧码失效`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val api = Api(client)
            val owner = api.registerOk("aqi")
            val roomId = owner.createRoom("屋檐").room.id
            val first = owner.post("/api/v1/rooms/$roomId/invites").body<Invite>()
            assertTrue(Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$").matches(first.code), first.code)
            assertEquals(Duration.ofDays(7), Duration.between(first.createdAt, first.expiresAt))

            val second = owner.post("/api/v1/rooms/$roomId/invites").body<Invite>()
            api.register("xiaochi", inviteCode = first.code).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InviteInvalid)

            clock.advance(Duration.ofDays(8))
            api.register("xiaochi", inviteCode = second.code).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InviteInvalid)
        }
    }

    @Test
    fun `邀请码大小写与空白不敏感`() = serverTest { client ->
        val api = Api(client)
        val owner = api.registerOk("aqi")
        val roomId = owner.createRoom("屋檐").room.id
        val code = owner.post("/api/v1/rooms/$roomId/invites").body<Invite>().code
        api.registerOk("xiaochi", " ${code.lowercase()} ")
    }

    @Test
    fun `第三个人接受邀请得到 room_full`() = serverTest { client ->
        val api = Api(client)
        val (owner, _, roomId) = api.pair()
        // 第三个人：用 owner 另一个房间的邀请码注册
        val otherRoom = owner.createRoom("另一个")
        val otherCode = owner.post("/api/v1/rooms/${otherRoom.room.id}/invites").body<Invite>().code
        val third = api.registerOk("third", otherCode)

        // 满员的房间已经生成不了邀请码；直接在库里放一张有效的邀请码，模拟「满员前发出的邀请码」
        TestDatabase.database.dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO invites (id, room_id, code, created_by, expires_at) VALUES (?, ?, 'K7PQ2XRM', ?, now() + interval '1 day')",
            ).use { st ->
                st.setObject(1, UuidV7.generate())
                st.setObject(2, roomId)
                st.setObject(3, owner.get("/api/v1/me").body<app.qichi.shared.api.Me>().user.id)
                st.executeUpdate()
            }
            conn.commit()
        }
        third.post("/api/v1/invites/accept", AcceptInviteRequest("K7PQ2XRM"))
            .assertProblem(HttpStatusCode.Conflict, ProblemCode.RoomFull)
    }

    @Test
    fun `已登录用户用邀请码加入；已是成员时直接返回`() = serverTest { client ->
        val api = Api(client)
        val owner = api.registerOk("aqi")
        val roomA = owner.createRoom("A").room.id
        val roomB = owner.createRoom("B").room.id
        val codeA = owner.post("/api/v1/rooms/$roomA/invites").body<Invite>().code
        val member = api.registerOk("xiaochi", codeA)
        val codeB = owner.post("/api/v1/rooms/$roomB/invites").body<Invite>().code

        val joined = member.post("/api/v1/invites/accept", AcceptInviteRequest(codeB))
        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(roomB, joined.body<RoomDetail>().room.id)
        val again = member.post("/api/v1/invites/accept", AcceptInviteRequest(codeB))
        assertEquals(HttpStatusCode.OK, again.status)
    }

    @Test
    fun `非成员访问任何房间接口都是 404`() = serverTest { client ->
        val api = Api(client)
        val (owner, _, roomId) = api.pair()
        val otherRoom = owner.createRoom("另一个")
        val otherCode = owner.post("/api/v1/rooms/${otherRoom.room.id}/invites").body<Invite>().code
        val outsider = api.registerOk("outsider", otherCode)

        outsider.get("/api/v1/rooms/$roomId").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(name = Patch.of("x")))
            .assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("/api/v1/rooms/$roomId/invites").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        // 不存在的房间与格式不对的 id 同样是 404
        outsider.get("/api/v1/rooms/${UuidV7.generate()}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.get("/api/v1/rooms/not-a-uuid").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `修改房间设置：两人都可以改；null 表示清空；时区必须合法`() = serverTest { client ->
        val api = Api(client)
        val (_, member, roomId) = api.pair()
        val updated = member.patch(
            "/api/v1/rooms/$roomId",
            UpdateRoomRequest(name = Patch.of("新名字"), anniversary = Patch.of(LocalDate.of(2020, 5, 20)), timezone = Patch.of("Europe/London")),
        ).body<Room>()
        assertEquals("新名字", updated.name)
        assertEquals(LocalDate.of(2020, 5, 20), updated.anniversary)
        assertEquals("Europe/London", updated.timezone)

        val cleared = member.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(anniversary = Patch.of(null))).body<Room>()
        assertEquals(null, cleared.anniversary)
        assertEquals("新名字", cleared.name)

        member.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(timezone = Patch.of("Mars/Olympus")))
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
    }

    @Test
    fun `并发 50 次写入后 seq 连续无重复`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val before = changeLogSeqs(roomId)
        coroutineScope {
            (1..50).map { i ->
                async {
                    val session = if (i % 2 == 0) owner else member
                    val response = session.patch("/api/v1/rooms/$roomId", UpdateRoomRequest(name = Patch.of("第 $i 次")))
                    assertEquals(HttpStatusCode.OK, response.status)
                }
            }.awaitAll()
        }
        val seqs = changeLogSeqs(roomId)
        assertEquals((1L..(before.size + 50).toLong()).toList(), seqs)
        assertEquals(seqs.last(), owner.get("/api/v1/rooms/$roomId").body<RoomDetail>().lastSeq)
    }

    @Test
    fun `改显示名会让所在房间产生 member 变化`() = serverTest { client ->
        val api = Api(client)
        val (_, member, roomId) = api.pair()
        val before = member.get("/api/v1/rooms/$roomId").body<RoomDetail>()
        member.patch("/api/v1/me", UpdateMeRequest(displayName = Patch.of("小迟")))
        val after = member.get("/api/v1/rooms/$roomId").body<RoomDetail>()
        val me = after.members.single { it.username == "xiaochi" }
        assertEquals("小迟", me.displayName)
        assertEquals(before.lastSeq + 1, after.lastSeq)
        assertEquals(after.lastSeq, me.seq)
    }
}
