package app.qichi.server.auth

import app.qichi.server.Api
import app.qichi.server.db.RefreshTokens
import app.qichi.server.db.tx
import org.jetbrains.exposed.v1.jdbc.update
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

    /** 数一数算了几次密码哈希（参数调低，和 fastHasher 一样快）。 */
    private class CountingHasher : PasswordHasher(memoryKib = 1024, iterations = 1, parallelism = 1) {
        @Volatile var hashes = 0
        override fun hash(password: String): String = super.hash(password).also { hashes++ }
    }

    @Test
    fun `注定失败的注册不计算密码哈希（没有邀请码、邀请码无效、用户名重复）`() {
        val hasher = CountingHasher()
        serverTest(ctx = testContext(hasher = hasher)) { client ->
            val api = Api(client)
            api.registerOk("aqi")
            assertEquals(1, hasher.hashes)
            api.register("xiaochi").assertProblem(HttpStatusCode.Forbidden, ProblemCode.RegistrationClosed)
            api.register("xiaochi", inviteCode = "ABCDEFGH").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InviteInvalid)
            api.register("aqi", inviteCode = "ABCDEFGH").assertProblem(HttpStatusCode.Conflict, ProblemCode.UsernameTaken)
            assertEquals(1, hasher.hashes, "被拒绝的注册一次哈希都不算")
        }
    }

    @Test
    fun `同一个 IP 换着用户名乱试也会被限流，窗口过后恢复`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val api = Api(client)
            api.registerOk("aqi")
            repeat(20) { api.login("nobody$it", "whatever").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized) }
            // 这个 IP 已经错了 20 次：连正确的密码也要等
            api.login("aqi").assertProblem(HttpStatusCode.TooManyRequests, ProblemCode.RateLimited)

            clock.advance(Duration.ofMinutes(16))
            api.loginOk("aqi")
        }
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

    private suspend fun io.ktor.client.HttpClient.refresh(token: String) =
        post("/api/v1/auth/refresh") { json(RefreshRequest(token)) }

    @Test
    fun `刷新会轮换令牌；回应丢了、过了很久才拿旧令牌回来（新令牌一次都没用来刷新过）：照样换发，登录不作废（P21-16）`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val session = Api(client).registerOk("aqi")
            val first = session.tokens

            val refreshed = client.refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val second = refreshed.body<AuthTokens>()
            assertNotEquals(first.refreshToken, second.refreshToken)

            // 线上两次被踢：刷新的回应没存下，27 分钟、67 分钟以后才拿旧令牌回来（以前超过 5 分钟就整组作废）
            clock.advance(Duration.ofMinutes(67))
            val back = client.refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, back.status)
            val third = back.body<AuthTokens>()
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(third.accessToken) }.status)

            // 好几天以后也一样
            clock.advance(Duration.ofDays(3))
            val fourth = client.refresh(third.refreshToken).body<AuthTokens>()
            clock.advance(Duration.ofDays(3))
            assertEquals(HttpStatusCode.OK, client.refresh(third.refreshToken).status)
            assertNotEquals(third.refreshToken, fourth.refreshToken)
        }
    }

    @Test
    fun `刷新的回应丢了：拿旧令牌再试，换发一对新的，登录不作废；丢两次也行`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val first = Api(client).registerOk("aqi").tokens
            // 服务端换了 second，但回应没送到手机
            val lost = client.refresh(first.refreshToken).body<AuthTokens>()

            clock.advance(Duration.ofMinutes(2))
            val retried = client.refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, retried.status)
            val lostAgain = retried.body<AuthTokens>()
            // 这次的回应又丢了，再试一次还是可以
            clock.advance(Duration.ofMinutes(2))
            val third = client.refresh(first.refreshToken).body<AuthTokens>()
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(third.accessToken) }.status)

            // 没送到的那几对已经作废；拿到手的这对照常轮换
            val next = client.refresh(third.refreshToken)
            assertEquals(HttpStatusCode.OK, next.status)
            assertNotEquals(lost.refreshToken, lostAgain.refreshToken)
        }
    }

    @Test
    fun `换出来的新令牌已经被用过，旧令牌再出现就是被盗用：整组作废`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val first = Api(client).registerOk("aqi").tokens
            val second = client.refresh(first.refreshToken).body<AuthTokens>()
            val third = client.refresh(second.refreshToken).body<AuthTokens>()

            clock.advance(Duration.ofMinutes(1))
            client.refresh(first.refreshToken).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            client.refresh(third.refreshToken).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            client.get("/api/v1/me") { bearerAuth(third.accessToken) }
                .assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        }
    }

    @Test
    fun `登出之后旧令牌不享受宽限`() = serverTest { client ->
        val session = Api(client).registerOk("aqi")
        val first = session.tokens
        val second = client.refresh(first.refreshToken).body<AuthTokens>()
        session.tokens = second
        assertEquals(HttpStatusCode.NoContent, session.post("/api/v1/auth/logout", RefreshRequest(second.refreshToken)).status)
        client.refresh(first.refreshToken).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
    }

    @Test
    fun `重复使用只影响那一台设备`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val api = Api(client)
            val phoneA = api.registerOk("aqi")
            val phoneB = api.loginOk("aqi")
            val old = phoneA.tokens.refreshToken
            val second = client.refresh(old).body<AuthTokens>()
            // 新令牌已经用来刷新过，旧令牌还出现：被盗用
            client.refresh(second.refreshToken)
            clock.advance(Duration.ofMinutes(1))
            client.refresh(old).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            assertEquals(HttpStatusCode.OK, phoneB.get("/api/v1/me").status)
        }
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
    fun `长期未打开仍能刷新且旧版到期字段不影响登录`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val session = Api(client).registerOk("aqi")
            // 模拟升级前签发的 60 天令牌，不需要客户端重新登录或数据库迁移。
            TestDatabase.database.tx {
                RefreshTokens.update { it[expiresAt] = clock.instant().plus(Duration.ofDays(60)) }
            }
            clock.advance(Duration.ofDays(3650))
            val refreshed = client.post("/api/v1/auth/refresh") { json(RefreshRequest(session.tokens.refreshToken)) }
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val tokens = refreshed.body<AuthTokens>()
            assertEquals(TokenService.REFRESH_EXPIRES_AT, tokens.refreshTokenExpiresAt)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(tokens.accessToken) }.status)
        }
    }

    @Test
    fun `改显示名`() = serverTest { client ->
        val session = Api(client).registerOk("aqi")
        val me = session.patch("/api/v1/me", UpdateMeRequest(displayName = Patch.of("阿栖"))).body<Me>()
        assertEquals("阿栖", me.user.displayName)
    }
}
