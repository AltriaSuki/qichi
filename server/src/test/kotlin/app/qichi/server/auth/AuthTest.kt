package app.qichi.server.auth

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.json
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.Me
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.model.ProblemCode
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    @Test
    fun `第一个用户不需要邀请码，注册后可以用访问令牌`() = serverTest { client ->
        val api = Api(client)
        val session = api.registerOk("aqi")
        val me = session.get("/api/v1/me").body<Me>()
        assertEquals("aqi", me.user.username)
        assertTrue(me.rooms.isEmpty())
    }

    @Test
    fun `第二个用户没有邀请码时注册被拒`() = serverTest { client ->
        val api = Api(client)
        api.registerOk("aqi")
        api.register("xiaochi").assertProblem(HttpStatusCode.Forbidden, ProblemCode.RegistrationClosed)
    }

    @Test
    fun `无效的邀请码返回 invite_invalid`() = serverTest { client ->
        val api = Api(client)
        api.registerOk("aqi")
        api.register("xiaochi", inviteCode = "ABCDEFGH").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InviteInvalid)
    }

    @Test
    fun `注册参数不合法时列出每个字段`() = serverTest { client ->
        val problem = Api(client).register("A!", password = "short", displayName = "  ")
            .assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        assertEquals(setOf("username", "password", "displayName"), problem.errors!!.map { it.field }.toSet())
    }

    @Test
    fun `用户名不区分大小写，重复注册返回 username_taken`() = serverTest { client ->
        val api = Api(client)
        val (owner, _, roomId) = api.pair("aqi", "xiaochi")
        // 房间满了之后，用另一个房间的邀请码来测试重名
        val other = owner.createRoom("另一个房间")
        val code = owner.post("/api/v1/rooms/${other.room.id}/invites").body<app.qichi.shared.api.Invite>().code
        api.register("XiaoChi", inviteCode = code).assertProblem(HttpStatusCode.Conflict, ProblemCode.UsernameTaken)
        assertNotEquals(roomId, other.room.id)
    }

    @Test
    fun `同时两个人抢着注册第一个账号，只有一个成功`() = serverTest { client ->
        val api = Api(client)
        val statuses = coroutineScope {
            listOf("aqi", "xiaochi").map { name -> async { api.register(name).status } }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Forbidden), statuses.sortedBy { it.value })
    }

    @Test
    fun `登录：正确密码成功，错误密码 401`() = serverTest { client ->
        val api = Api(client)
        api.registerOk("aqi")
        api.loginOk("AQI", "password-aqi")
        api.login("aqi", "wrong-password").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.login("nobody", "whatever").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
    }

    @Test
    fun `错误密码 5 次后被限流，窗口过后恢复`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val api = Api(client)
            api.registerOk("aqi")
            repeat(5) { api.login("aqi", "wrong-$it").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized) }
            val limited = api.login("aqi")
            limited.assertProblem(HttpStatusCode.TooManyRequests, ProblemCode.RateLimited)
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)

            clock.advance(Duration.ofMinutes(16))
            api.loginOk("aqi")
        }
    }

    @Test
    fun `刷新会轮换令牌；旧刷新令牌第二次使用导致该设备所有令牌作废`() = serverTest { client ->
        val api = Api(client)
        val session = api.registerOk("aqi")
        val first = session.tokens

        val refreshed = client.post("/api/v1/auth/refresh") { json(RefreshRequest(first.refreshToken)) }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val second = refreshed.body<AuthTokens>()
        assertNotEquals(first.refreshToken, second.refreshToken)
        client.get("/api/v1/me") { bearerAuth(second.accessToken) }.let { assertEquals(HttpStatusCode.OK, it.status) }

        // 旧令牌再用一次：视为被盗用，整组作废
        client.post("/api/v1/auth/refresh") { json(RefreshRequest(first.refreshToken)) }
            .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        client.post("/api/v1/auth/refresh") { json(RefreshRequest(second.refreshToken)) }
            .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        client.get("/api/v1/me") { bearerAuth(second.accessToken) }
            .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
    }

    @Test
    fun `重复使用只影响那一台设备`() = serverTest { client ->
        val api = Api(client)
        val phoneA = api.registerOk("aqi")
        val phoneB = api.loginOk("aqi")
        val old = phoneA.tokens.refreshToken
        client.post("/api/v1/auth/refresh") { json(RefreshRequest(old)) }
        client.post("/api/v1/auth/refresh") { json(RefreshRequest(old)) }
        assertEquals(HttpStatusCode.OK, phoneB.get("/api/v1/me").status)
    }

    @Test
    fun `登出后访问令牌与刷新令牌立即失效`() = serverTest { client ->
        val api = Api(client)
        val session = api.registerOk("aqi")
        val response = session.post("/api/v1/auth/logout", RefreshRequest(session.tokens.refreshToken))
        assertEquals(HttpStatusCode.NoContent, response.status)
        session.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        client.post("/api/v1/auth/refresh") { json(RefreshRequest(session.tokens.refreshToken)) }
            .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
    }

    @Test
    fun `改密码作废其它设备的登录，当前设备拿到新令牌`() = serverTest { client ->
        val api = Api(client)
        val phoneA = api.registerOk("aqi")
        val phoneB = api.loginOk("aqi")

        phoneA.post("/api/v1/me/password", ChangePasswordRequest("wrong", "new-password-1"))
            .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)

        val response = phoneA.post("/api/v1/me/password", ChangePasswordRequest("password-aqi", "new-password-1"))
        assertEquals(HttpStatusCode.OK, response.status)
        phoneA.tokens = response.body()

        assertEquals(HttpStatusCode.OK, phoneA.get("/api/v1/me").status)
        phoneB.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.login("aqi").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.loginOk("aqi", "new-password-1")
    }

    @Test
    fun `访问令牌过期或被篡改时 401`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val session = Api(client).registerOk("aqi")
            client.get("/api/v1/me") { bearerAuth(session.tokens.accessToken.dropLast(2) + "xx") }
                .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            client.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)

            clock.advance(Duration.ofMinutes(16))
            session.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            // 刷新令牌还在有效期内，可以换新
            val refreshed = client.post("/api/v1/auth/refresh") { json(RefreshRequest(session.tokens.refreshToken)) }
            assertEquals(HttpStatusCode.OK, refreshed.status)
        }
    }

    @Test
    fun `刷新令牌 60 天后过期`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val session = Api(client).registerOk("aqi")
            clock.advance(Duration.ofDays(61))
            client.post("/api/v1/auth/refresh") { json(RefreshRequest(session.tokens.refreshToken)) }
                .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        }
    }

    @Test
    fun `改显示名`() = serverTest { client ->
        val session = Api(client).registerOk("aqi")
        val me = session.patch("/api/v1/me", UpdateMeRequest(displayName = Patch.of("阿栖"))).body<Me>()
        assertEquals("阿栖", me.user.displayName)
    }
}
