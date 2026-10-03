package app.qichi.core.auth

import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.shared.api.Me
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.QichiJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** 登录被动失效后本机数据留着；同一账号接着用，换账号先确认（P13-08）。 */
class SessionManagerTest {
    private val aqi = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000001")
    private val other = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000002")

    private fun tokens(user: UUID, n: Int = 1): AuthTokens {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val jwt = enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"$user","n":$n}""".toByteArray()) + ".sig"
        return AuthTokens(jwt, Instant.EPOCH, "r-$user-$n", Instant.EPOCH)
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** 假服务端：aqi 和 other 两个账号都能登录；记下收到的登出请求带的令牌。 */
    private inner class Server {
        val logouts = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/auth/login") -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val user = if (QichiJson.decodeFromString(LoginRequest.serializer(), body).username == "aqi") aqi else other
                    respond(QichiJson.encodeToString(AuthTokens.serializer(), tokens(user, 2)), HttpStatusCode.OK, json)
                }
                path.endsWith("/auth/logout") -> {
                    logouts += request.headers[HttpHeaders.Authorization]
                    respond("", HttpStatusCode.NoContent)
                }
                // 服务端不认这台手机的登录了：访问令牌过期、刷新也被拒
                path.endsWith("/auth/refresh") || path.endsWith("/me") -> respond("", HttpStatusCode.Unauthorized)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    /** 数一数清过几次本机数据 */
    private class Counter {
        var value = 0
    }

    private class Fixture(
        val api: ApiClient,
        val session: SessionManager,
        val owner: InMemoryLocalOwnerStore,
        val store: InMemoryTokenStore,
        val scope: CoroutineScope,
        private val cleared: Counter,
    ) {
        val clearedCount: Int get() = cleared.value
    }

    private val endedAt = Instant.parse("2026-10-02T15:14:00Z")

    private fun TestScope.fixture(server: Server, saved: AuthTokens?, owner: UUID?, unsent: Int, unreadable: Boolean = false): Fixture {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val store = InMemoryTokenStore(saved, unreadable)
        val ownerStore = InMemoryLocalOwnerStore(owner)
        val cleared = Counter()
        val api = ApiClient(server.engine, "http://test", store, "test")
        val session = SessionManager(
            api, store, setOf(LocalDataCleaner { cleared.value++ }), "test", scope,
            owner = ownerStore, unsentCount = { unsent }, clock = Clock.fixed(endedAt, ZoneOffset.UTC),
        )
        return Fixture(api, session, ownerStore, store, scope, cleared)
    }

    @Test
    fun `服务端不认这台手机的登录：记下原因和时间给登录页说明，重新登录后清掉`() = runTest {
        val f = fixture(Server(), saved = tokens(aqi), owner = aqi, unsent = 1)
        assertEquals(SessionState.LoggedIn(aqi), f.session.state.value)
        runCatching { f.api.get<Me>("me") }
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertEquals(ExpiredSession(aqi, 1, SessionEnd(SessionEndReason.Rejected, endedAt)), f.session.expired.value)

        f.session.login("aqi", "password")
        assertNull(f.owner.readEnd())
        assertNull(f.session.expired.value)
        f.scope.cancel()
    }

    @Test
    fun `存着令牌却解不开：当作登录失效，说明是读不出来；本机数据留着`() = runTest {
        val f = fixture(Server(), saved = null, owner = aqi, unsent = 3, unreadable = true)
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertEquals(ExpiredSession(aqi, 3, SessionEnd(SessionEndReason.Unreadable, endedAt)), f.session.expired.value)
        assertEquals(0, f.clearedCount)
        f.scope.cancel()
    }

    @Test
    fun `主动登出后不留失效原因`() = runTest {
        val f = fixture(Server(), saved = tokens(aqi), owner = aqi, unsent = 0)
        runCatching { f.api.get<Me>("me") }
        assertEquals(SessionEndReason.Rejected, f.owner.readEnd()?.reason)
        f.session.logout()
        assertNull(f.owner.readEnd())
        f.scope.cancel()
    }

    @Test
    fun `登录失效后再登录同一个账号：本机数据接着用，不清`() = runTest {
        val f = fixture(Server(), saved = null, owner = aqi, unsent = 2)
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertEquals(ExpiredSession(aqi, 2), f.session.expired.value)

        assertEquals(SignInResult.Done, f.session.login("aqi", "password"))
        assertEquals(SessionState.LoggedIn(aqi), f.session.state.value)
        assertEquals(0, f.clearedCount)
        assertNull(f.session.expired.value)
        assertEquals(aqi, f.owner.read())
        f.scope.cancel()
    }

    @Test
    fun `换账号且本机还有没发出去的：先要确认，确认后才清掉并登录`() = runTest {
        val f = fixture(Server(), saved = null, owner = aqi, unsent = 2)

        val result = f.session.login("other", "password")
        assertIs<SignInResult.NeedsConfirm>(result)
        assertEquals(2, result.unsent)
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertNull(f.store.read(), "确认之前不保存新账号的令牌")
        assertEquals(0, f.clearedCount)

        f.session.confirmSwitch(result)
        assertEquals(SessionState.LoggedIn(other), f.session.state.value)
        assertEquals(1, f.clearedCount)
        assertEquals(other, f.owner.read())
        f.scope.cancel()
    }

    @Test
    fun `换账号时点了先不换：新账号的令牌作废，本机数据和登出状态都不变`() = runTest {
        val server = Server()
        val f = fixture(server, saved = null, owner = aqi, unsent = 1)
        val result = f.session.login("other", "password") as SignInResult.NeedsConfirm

        f.session.cancelSwitch(result)
        assertEquals(listOf<String?>("Bearer ${tokens(other, 2).accessToken}"), server.logouts)
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertEquals(0, f.clearedCount)
        assertEquals(aqi, f.owner.read())
        f.scope.cancel()
    }

    @Test
    fun `换账号但本机没有没发出去的：直接清掉上一个账号的数据并登录`() = runTest {
        val f = fixture(Server(), saved = null, owner = aqi, unsent = 0)
        assertEquals(SignInResult.Done, f.session.login("other", "password"))
        assertEquals(SessionState.LoggedIn(other), f.session.state.value)
        assertEquals(1, f.clearedCount)
        f.scope.cancel()
    }

    @Test
    fun `主动登出清掉本机数据和归属，之后换账号登录不提示`() = runTest {
        val f = fixture(Server(), saved = tokens(aqi), owner = aqi, unsent = 5)
        f.session.logout()
        assertEquals(1, f.clearedCount)
        assertNull(f.owner.read())
        assertNull(f.session.expired.value)
        assertEquals(SignInResult.Done, f.session.login("other", "password"))
        f.scope.cancel()
    }

    @Test
    fun `这个版本之前就登录着的：启动时补记归属`() = runTest {
        val f = fixture(Server(), saved = tokens(aqi), owner = null, unsent = 4)
        assertEquals(SessionState.LoggedIn(aqi), f.session.state.value)
        assertEquals(aqi, f.owner.read())
        assertEquals(0, f.clearedCount)
        f.scope.cancel()
    }

    @Test
    fun `新装的 App（没有归属）不提示登录失效`() = runTest {
        val f = fixture(Server(), saved = null, owner = null, unsent = 0)
        assertEquals(SessionState.LoggedOut, f.session.state.value)
        assertNull(f.session.expired.value)
        f.scope.cancel()
    }
}
