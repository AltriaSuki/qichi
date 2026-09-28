package app.qichi.core.auth

import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.RegisterRequest
import app.qichi.shared.api.ResetPasswordRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID

sealed interface SessionState {
    /** 正在读取本机保存的令牌 */
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: UUID) : SessionState
}

/** 登出时要清掉的本机数据（Room 数据库、草稿、发件箱等）。各模块用 Hilt 多绑定注册。 */
fun interface LocalDataCleaner {
    suspend fun clear()
}

/** 主动登出前、令牌还在时要做的事（例如注销推送设备）。失败不影响登出。 */
fun interface LogoutHook {
    suspend fun beforeLogout()
}

/** 登录被动失效、本机数据还留着：[unsent] 条写操作没发出去（登录页据此提示）。 */
data class ExpiredSession(val userId: UUID, val unsent: Int)

/** 登录、注册的结果。 */
sealed interface SignInResult {
    data object Done : SignInResult

    /** 本机还有另一个账号 [unsent] 条没发出去的内容：用户确认清掉之后才登录（[SessionManager.confirmSwitch]）。 */
    class NeedsConfirm(val unsent: Int, internal val tokens: AuthTokens) : SignInResult
}

/**
 * 登录状态的唯一来源：注册、登录、登出。
 *
 * 登录被动失效（刷新令牌不能用了）时只清令牌、本机数据留着（P13-08，docs/05-sync-offline.md §3.3）：
 * 同一个账号重新登录就接着用，发件箱接着发；换成别的账号登录时，本机还有没发出去的内容就先让用户确认，确认后再清。
 * 主动登出照旧清掉本机数据。本机数据属于哪个账号记在 [owner] 里。
 */
class SessionManager(
    private val api: ApiClient,
    private val tokenStore: TokenStore,
    private val cleaners: Set<LocalDataCleaner>,
    private val deviceName: String,
    scope: CoroutineScope,
    private val logoutHooks: Set<LogoutHook> = emptySet(),
    private val owner: LocalOwnerStore = InMemoryLocalOwnerStore(),
    /** 本机还没发出去的写操作有几条 */
    private val unsentCount: suspend () -> Int = { 0 },
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val _expired = MutableStateFlow<ExpiredSession?>(null)

    /** 登录被动失效、本机数据还留着时不为空：登录页提示「重新登录后接着发」。 */
    val expired: StateFlow<ExpiredSession?> = _expired.asStateFlow()

    init {
        scope.launch {
            val tokens = tokenStore.read()
            if (tokens != null) {
                // 这个版本之前登录的没记归属：补上，之后登录失效再登录同一个账号时才认得出本机数据是自己的
                if (owner.read() == null) owner.write(userIdOf(tokens))
                _state.value = SessionState.LoggedIn(userIdOf(tokens))
            } else {
                noteExpired()
                _state.value = SessionState.LoggedOut
            }
        }
        scope.launch {
            api.sessionExpired.collect {
                noteExpired()
                _state.value = SessionState.LoggedOut
            }
        }
    }

    /** 本机数据还归某个账号（登录失效或读不出令牌，而不是新装或主动登出过）：记下来给登录页提示。 */
    private suspend fun noteExpired() {
        val previous = owner.read() ?: return
        _expired.value = ExpiredSession(previous, unsentCount())
    }

    val currentUserId: UUID? get() = (state.value as? SessionState.LoggedIn)?.userId

    /**
     * 等本机令牌读完，给出确定的登录状态（不会是 [SessionState.Loading]）。
     * 被通知回复、推送、后台任务冷启动拉起来的代码用它：这时令牌往往还在读，不能当成没登录把事情丢掉（P13-04）。
     */
    suspend fun awaitLoaded(): SessionState = state.first { it !is SessionState.Loading }

    suspend fun register(username: String, password: String, displayName: String, inviteCode: String?): SignInResult {
        val tokens = api.post<AuthTokens>(
            "auth/register",
            RegisterRequest(
                username = username.trim().lowercase(),
                password = password,
                displayName = displayName.trim(),
                inviteCode = inviteCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                deviceName = deviceName,
            ),
            auth = false,
        )
        return signIn(tokens)
    }

    suspend fun login(username: String, password: String): SignInResult {
        val tokens = api.post<AuthTokens>(
            "auth/login",
            LoginRequest(username.trim().lowercase(), password, deviceName),
            auth = false,
        )
        return signIn(tokens)
    }

    /** 忘了密码：用对方给的重置码设新密码，成功后直接登录（P16-04）。 */
    suspend fun resetPassword(username: String, code: String, newPassword: String): SignInResult {
        val tokens = api.post<AuthTokens>(
            "auth/password-reset",
            ResetPasswordRequest(username.trim().lowercase(), code.trim().uppercase(), newPassword, deviceName),
            auth = false,
        )
        return signIn(tokens)
    }

    /** 改密码后服务端返回新令牌。 */
    suspend fun replaceTokens(tokens: AuthTokens) = tokenStore.write(tokens)

    /** 登出：尽量通知服务端（失败也继续），然后清掉令牌和本机数据。 */
    suspend fun logout() {
        val tokens = tokenStore.read()
        if (tokens != null) {
            logoutHooks.forEach { hook ->
                try {
                    hook.beforeLogout()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            try {
                api.post<Unit>("auth/logout", RefreshRequest(tokens.refreshToken))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 离线或令牌已失效：本机照样登出
            }
        }
        tokenStore.clear()
        clearLocal()
        owner.write(null)
        _expired.value = null
        _state.value = SessionState.LoggedOut
    }

    /** 用户确认清掉上一个账号没发出去的内容，登录新账号。 */
    suspend fun confirmSwitch(pending: SignInResult.NeedsConfirm) {
        signIn(pending.tokens, confirmed = true)
    }

    /** 不换了：作废刚拿到的新账号令牌（尽力而为），本机数据原样留着。 */
    suspend fun cancelSwitch(pending: SignInResult.NeedsConfirm) {
        try {
            api.send(HttpMethod.Post, "auth/logout", Unit.serializer(), RefreshRequest(pending.tokens.refreshToken), auth = false) {
                header(HttpHeaders.Authorization, "Bearer ${pending.tokens.accessToken}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun signIn(tokens: AuthTokens, confirmed: Boolean = false): SignInResult {
        val userId = userIdOf(tokens)
        val previous = owner.read()
        if (previous != userId) {
            // 换了账号：本机还有上一个账号没发出去的内容，先让用户确认
            if (previous != null && !confirmed) {
                val unsent = unsentCount()
                if (unsent > 0) return SignInResult.NeedsConfirm(unsent, tokens)
            }
            // 清掉上一个账号留在本机的数据；同一个账号（登录失效后重新登录）就接着用
            clearLocal()
            owner.write(userId)
        }
        tokenStore.write(tokens)
        _expired.value = null
        _state.value = SessionState.LoggedIn(userId)
        return SignInResult.Done
    }

    private suspend fun clearLocal() {
        cleaners.forEach { cleaner ->
            try {
                cleaner.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** 从访问令牌（JWT）里读出用户 id（sub）。只读不验签，验签在服务端。 */
        fun userIdOf(tokens: AuthTokens): UUID {
            val payload = tokens.accessToken.split('.')[1]
            val json = String(Base64.getUrlDecoder().decode(payload))
            val sub = QichiJson.parseToJsonElement(json).jsonObject["sub"]!!.jsonPrimitive.content
            return UUID.fromString(sub)
        }
    }
}
