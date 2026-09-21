package app.qichi.core.network

import app.cash.turbine.test
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.LocalDataCleaner
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.Health
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.model.ProblemCode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {

    private val userId = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000001")

    /** 造一个只有 sub 的假 JWT（客户端只读 sub，不验签）。 */
    private fun jwt(tag: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"$userId","tag":"$tag"}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun tokens(n: Int) = AuthTokens(jwt("a$n"), Instant.EPOCH, "r$n", Instant.EPOCH)

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: String): HttpResponseData =
        respond(
            """{"type":"https://qichi.app/errors/$code","title":"标题","status":${status.value},"code":"$code"}""",
            status,
            problemJson,
        )

    private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    /**
     * 假服务端：只认 [validAccess]；refresh 用 [validRefresh] 换来下一对令牌。
     */
    private inner class FakeServer(var validAccess: String, var validRefresh: String, var refreshFails: Boolean = false) {
        val refreshCalls = AtomicInteger()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/auth/refresh") -> {
                    refreshCalls.incrementAndGet()
                    val sent = QichiJson.decodeFromString(RefreshRequest.serializer(), request.bodyText())
                    if (refreshFails || sent.refreshToken != validRefresh) {
                        problem(HttpStatusCode.Unauthorized, "unauthorized")
                    } else {
                        val n = validRefresh.removePrefix("r").toInt() + 1
                        val next = tokens(n)
                        validAccess = next.accessToken
                        validRefresh = next.refreshToken
                        respond(QichiJson.encodeToString(AuthTokens.serializer(), next), HttpStatusCode.OK, json)
                    }
                }
                path.endsWith("/auth/login") -> problem(HttpStatusCode.Unauthorized, "unauthorized")
                path.endsWith("/rooms/full/invites") -> problem(HttpStatusCode.Conflict, "room_full")
                request.headers[HttpHeaders.Authorization] == "Bearer $validAccess" ->
                    respond("""{"status":"ok","version":"test"}""", HttpStatusCode.OK, json)
                else -> problem(HttpStatusCode.Unauthorized, "unauthorized")
            }
        }
    }

    private fun client(server: FakeServer, store: InMemoryTokenStore) =
        ApiClient(server.engine, "http://test", store, "test")

    @Test
    fun `访问令牌过期时自动刷新一次再重试`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val server = FakeServer(validAccess = tokens(2).accessToken, validRefresh = "r1")
        val api = client(server, store)

        val health = api.get<Health>("anything")
        assertEquals("ok", health.status)
        assertEquals(1, server.refreshCalls.get())
        assertEquals("r2", store.read()!!.refreshToken)
    }

    @Test
    fun `多个请求同时 401 只刷新一次`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val server = FakeServer(validAccess = tokens(2).accessToken, validRefresh = "r1")
        val api = client(server, store)

        val results = (1..5).map { async { api.get<Health>("anything") } }.awaitAll()
        assertTrue(results.all { it.status == "ok" })
        assertEquals(1, server.refreshCalls.get())
    }

    @Test
    fun `刷新失败时清掉令牌、通知登录失效，请求抛出 SessionExpiredException`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val server = FakeServer(validAccess = "nothing-valid", validRefresh = "r1", refreshFails = true)
        val api = client(server, store)

        api.sessionExpired.test {
            assertFailsWith<SessionExpiredException> { api.get<Health>("anything") }
            awaitItem()
        }
        assertNull(store.read())
    }

    @Test
    fun `登录接口的 401 不触发刷新`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val server = FakeServer(validAccess = tokens(1).accessToken, validRefresh = "r1")
        val api = client(server, store)

        val e = assertFailsWith<ApiException> { api.post<AuthTokens>("auth/login", mapOf("x" to "y"), auth = false) }
        assertEquals(401, e.status)
        assertEquals(0, server.refreshCalls.get())
        assertEquals(tokens(1), store.read())
    }

    @Test
    fun `problem+json 解析为带 code 的 ApiException`() = runTest {
        val server = FakeServer(validAccess = tokens(1).accessToken, validRefresh = "r1")
        val api = client(server, InMemoryTokenStore(tokens(1)))
        val e = assertFailsWith<ApiException> { api.post<Unit>("rooms/full/invites") }
        assertEquals(ProblemCode.RoomFull, e.code)
        assertEquals(409, e.status)
        assertEquals("标题", e.userMessage)
    }

    @Test
    fun `连不上服务器时抛 NetworkException`() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }
        val api = ApiClient(engine, "http://test", InMemoryTokenStore(tokens(1)), "test")
        assertFailsWith<NetworkException> { api.get<Health>("health") }
    }

    @Test
    fun `请求带客户端版本头`() = runTest {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers["X-Qichi-Client"]
            respond("""{"status":"ok","version":"x"}""", HttpStatusCode.OK, json)
        }
        ApiClient(engine, "http://test", InMemoryTokenStore(), "0.1.0").get<Health>("health", auth = false)
        assertEquals("android/0.1.0", seen)
    }

    @Test
    fun `刷新失败后会话回到登出状态并清掉本机数据`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val server = FakeServer(validAccess = "nothing-valid", validRefresh = "r1", refreshFails = true)
        val api = client(server, store)
        var cleared = 0
        val scope = eagerScope()
        val session = SessionManager(api, store, setOf(LocalDataCleaner { cleared++ }), "test", scope)
        assertEquals(SessionState.LoggedIn(userId), session.state.value)

        runCatching { api.get<Health>("anything") }
        session.state.first { it == SessionState.LoggedOut }
        assertEquals(1, cleared)
        scope.cancel()
    }

    @Test
    fun `登出：离线也照样清掉令牌与本机数据`() = runTest {
        val store = InMemoryTokenStore(tokens(1))
        val engine = MockEngine { throw IOException("offline") }
        val api = ApiClient(engine, "http://test", store, "test")
        var cleared = 0
        val scope = eagerScope()
        val session = SessionManager(api, store, setOf(LocalDataCleaner { cleared++ }), "test", scope)

        session.logout()
        assertNull(store.read())
        assertEquals(1, cleared)
        assertEquals(SessionState.LoggedOut, session.state.value)
        scope.cancel()
    }

    @Test
    fun `从访问令牌读出用户 id`() {
        assertEquals(userId, SessionManager.userIdOf(tokens(7)))
    }

    /** 立即执行的作用域：SessionManager 在 init 里启动的协程马上跑完。 */
    private fun TestScope.eagerScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
}
