package app.qichi.server

import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.CLIENT_HEADER
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.androidClientHeader
import app.qichi.shared.model.ProblemCode
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** App 最低版本（P13-07）：太旧的 App 收到 426 upgrade_required，但还能登录、刷新令牌、下载新版。 */
class ClientVersionTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    private fun ctx() = testContext(config = testConfig().copy(minAndroidVersionCode = 300))

    @Test
    fun `低于最低版本的 App 收到 426；够新的、没带版本头的照常`() = serverTest(ctx()) { client ->
        val (aqi, _, room) = Api(client).pair()
        val sync = "/api/v1/rooms/$room/sync?since=0"

        val problem = aqi.get(sync) { header(CLIENT_HEADER, androidClientHeader("0.2.299", 299)) }
            .assertProblem(HttpStatusCode.UpgradeRequired, ProblemCode.UpgradeRequired)
        assertEquals("至少需要版本 300，现在是 299", problem.detail)
        // P13-07 之前的 App 只发 versionName
        aqi.get(sync) { header(CLIENT_HEADER, "android/0.2.120") }.assertProblem(HttpStatusCode.UpgradeRequired, ProblemCode.UpgradeRequired)

        assertEquals(HttpStatusCode.OK, aqi.get(sync) { header(CLIENT_HEADER, androidClientHeader("0.2.300", 300)) }.status)
        assertEquals(HttpStatusCode.OK, aqi.get(sync).status)
        assertEquals(HttpStatusCode.OK, aqi.get(sync) { header(CLIENT_HEADER, "android/0.2.dev") }.status)
    }

    @Test
    fun `太旧的 App 还能健康检查、登录、刷新令牌、查新版`() = serverTest(ctx()) { client ->
        Api(client).registerOk("aqi")
        val old = androidClientHeader("0.2.1", 1)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health") { header(CLIENT_HEADER, old) }.status)
        val login = client.post("/api/v1/auth/login") { header(CLIENT_HEADER, old); json(LoginRequest("aqi", "password-aqi")) }
        assertEquals(HttpStatusCode.OK, login.status)
        val tokens = login.body<AuthTokens>()
        val refresh = client.post("/api/v1/auth/refresh") { header(CLIENT_HEADER, old); json(RefreshRequest(tokens.refreshToken)) }
        assertEquals(HttpStatusCode.OK, refresh.status)
        val fresh = refresh.body<AuthTokens>()
        // 服务端还没发布过新版：404，而不是 426
        client.get("/api/v1/app/latest") {
            header(CLIENT_HEADER, old)
            header(io.ktor.http.HttpHeaders.Authorization, "Bearer ${fresh.accessToken}")
        }.assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        client.get("/api/v1/me") {
            header(CLIENT_HEADER, old)
            header(io.ktor.http.HttpHeaders.Authorization, "Bearer ${fresh.accessToken}")
        }.assertProblem(HttpStatusCode.UpgradeRequired, ProblemCode.UpgradeRequired)
    }

    @Test
    fun `默认不限制版本`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        assertEquals(HttpStatusCode.OK, aqi.get("/api/v1/rooms/$room/sync?since=0") { header(CLIENT_HEADER, androidClientHeader("0.2.1", 1)) }.status)
    }
}
