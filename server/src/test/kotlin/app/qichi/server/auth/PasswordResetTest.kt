package app.qichi.server.auth

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.json
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.Me
import app.qichi.shared.api.PasswordResetCode
import app.qichi.shared.api.ResetPasswordRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** 忘了密码：房间里另一个人生成重置码，用它设新密码（P16-04）。 */
class PasswordResetTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private suspend fun HttpClient.reset(username: String, code: String, password: String = "brand-new-pass") =
        post("/api/v1/auth/password-reset") { json(ResetPasswordRequest(username, code, password, deviceName = "新手机")) }

    @Test
    fun `对方生成的重置码能设新密码：旧密码和所有旧登录作废，这台直接登录，码只能用一次`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val memberId = member.get("/api/v1/me").body<Me>().user.id
        val otherPhone = api.loginOk("xiaochi")

        val created = owner.post("/api/v1/rooms/$roomId/members/$memberId/password-reset")
        assertEquals(HttpStatusCode.Created, created.status)
        val code = created.body<PasswordResetCode>().code
        assertEquals(8, code.length)

        val response = client.reset("XiaoChi", code.lowercase())
        assertEquals(HttpStatusCode.OK, response.status)
        val tokens = response.body<AuthTokens>()
        assertNotEquals("", tokens.accessToken)

        member.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        otherPhone.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.login("xiaochi").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        api.loginOk("xiaochi", "brand-new-pass")

        client.reset("xiaochi", code, "another-pass-2").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
    }

    @Test
    fun `只能给同一个房间里的另一个人生成；给自己 400，别的房间的人 404`() = serverTest { client ->
        val api = Api(client)
        val (owner, member, roomId) = api.pair()
        val ownerId = owner.get("/api/v1/me").body<Me>().user.id
        val memberId = member.get("/api/v1/me").body<Me>().user.id
        // 房间成员反过来给房主生成也行
        assertEquals(HttpStatusCode.Created, member.post("/api/v1/rooms/$roomId/members/$ownerId/password-reset").status)
        owner.post("/api/v1/rooms/$roomId/members/$ownerId/password-reset").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        owner.post("/api/v1/rooms/$roomId/members/${UuidV7.generate()}/password-reset").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        // 不是这个房间的成员：和访问别的房间一样 404
        owner.post("/api/v1/rooms/${UuidV7.generate()}/members/$memberId/password-reset").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
    }

    @Test
    fun `过期的、被新码换掉的、猜错的都不行；猜错 5 次就限流`() {
        val clock = MutableClock()
        serverTest(ctx = testContext(clock = clock)) { client ->
            val api = Api(client)
            val (first, member, roomId) = api.pair()
            val memberId = member.get("/api/v1/me").body<Me>().user.id
            val path = "/api/v1/rooms/$roomId/members/$memberId/password-reset"

            val expired = first.post(path).body<PasswordResetCode>().code
            clock.advance(Duration.ofMinutes(16))
            // 访问令牌也过期了：重新登录
            var owner = api.loginOk("aqi")
            client.reset("xiaochi", expired).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)

            val replaced = owner.post(path).body<PasswordResetCode>().code
            val current = owner.post(path).body<PasswordResetCode>().code
            client.reset("xiaochi", replaced).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
            // 新密码太短：400，码不算用掉
            client.reset("xiaochi", current, "short").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
            client.reset("nobody", current).assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)

            repeat(3) { client.reset("xiaochi", "WRONG00$it") }
            client.reset("xiaochi", current).assertProblem(HttpStatusCode.TooManyRequests, ProblemCode.RateLimited)
            clock.advance(Duration.ofMinutes(16))
            // 限流过去以后码也过期了：再生成一个
            owner = api.loginOk("aqi")
            val fresh = owner.post(path).body<PasswordResetCode>().code
            assertEquals(HttpStatusCode.OK, client.reset("xiaochi", fresh).status)
        }
    }

    @Test
    fun `服务器上的命令按用户名生成重置码；用户名不存在返回 null`() {
        val ctx = testContext()
        serverTest(ctx = ctx) { client ->
            val api = Api(client)
            api.registerOk("aqi")
            val code = ctx.auth.createResetCodeForUsername(" AQI ")!!.code
            assertEquals(HttpStatusCode.OK, client.reset("aqi", code).status)
            assertNull(ctx.auth.createResetCodeForUsername("nobody"))
        }
    }
}
