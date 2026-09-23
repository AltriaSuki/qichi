package app.qichi.core.auth

import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.RegisterRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/**
 * 登录状态的唯一来源：注册、登录、登出；刷新令牌失效时自动回到登出状态并清掉本机数据。
 */
class SessionManager(
    private val api: ApiClient,
    private val tokenStore: TokenStore,
    private val cleaners: Set<LocalDataCleaner>,
    private val deviceName: String,
    scope: CoroutineScope,
    private val logoutHooks: Set<LogoutHook> = emptySet(),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        scope.launch {
            _state.value = tokenStore.read()?.let { SessionState.LoggedIn(userIdOf(it)) } ?: SessionState.LoggedOut
        }
        scope.launch {
            api.sessionExpired.collect {
                clearLocal()
                _state.value = SessionState.LoggedOut
            }
        }
    }

    val currentUserId: UUID? get() = (state.value as? SessionState.LoggedIn)?.userId

    suspend fun register(username: String, password: String, displayName: String, inviteCode: String?) {
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
        signIn(tokens)
    }

    suspend fun login(username: String, password: String) {
        val tokens = api.post<AuthTokens>(
            "auth/login",
            LoginRequest(username.trim().lowercase(), password, deviceName),
            auth = false,
        )
        signIn(tokens)
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
        _state.value = SessionState.LoggedOut
    }

    private suspend fun signIn(tokens: AuthTokens) {
        // 换了账号：先清掉上一个账号留在本机的数据
        clearLocal()
        tokenStore.write(tokens)
        _state.value = SessionState.LoggedIn(userIdOf(tokens))
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
