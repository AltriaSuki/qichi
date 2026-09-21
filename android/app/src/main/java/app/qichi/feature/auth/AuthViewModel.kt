package app.qichi.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.network.ApiException
import app.qichi.core.ui.FormError
import app.qichi.core.ui.toFormError
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
    val inviteCode: String = "",
    val submitting: Boolean = false,
    val error: FormError = FormError(),
)

/** 登录与注册共用的表单状态。成功后 SessionManager 的状态变化会把界面切走。 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onUsername(v: String) = _state.update { it.copy(username = v.lowercase().filter { c -> !c.isWhitespace() }, error = FormError()) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = FormError()) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, error = FormError()) }
    fun onInviteCode(v: String) = _state.update { it.copy(inviteCode = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(Limits.INVITE_LENGTH), error = FormError()) }

    fun login() {
        val s = _state.value
        val fields = buildMap {
            if (s.username.isBlank()) put("username", "请填写用户名")
            if (s.password.isEmpty()) put("password", "请填写密码")
        }
        if (fields.isNotEmpty()) return _state.update { it.copy(error = FormError(fields)) }
        submit { session.login(s.username, s.password) }
    }

    fun register() {
        val s = _state.value
        val fields = buildMap {
            if (s.displayName.trim().length !in Limits.DISPLAY_NAME_LENGTH) put("displayName", "显示名 1–32 个字")
            if (!Limits.USERNAME_PATTERN.matches(s.username)) put("username", "3–32 位小写字母、数字或下划线")
            if (s.password.length !in Limits.PASSWORD_LENGTH) put("password", "密码至少 8 位")
            if (s.inviteCode.isNotEmpty() && s.inviteCode.length != Limits.INVITE_LENGTH) put("inviteCode", "邀请码是 8 位")
        }
        if (fields.isNotEmpty()) return _state.update { it.copy(error = FormError(fields)) }
        submit { session.register(s.username, s.password, s.displayName, s.inviteCode.ifEmpty { null }) }
    }

    private fun submit(action: suspend () -> Unit) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = FormError()) }
        viewModelScope.launch {
            val error = runCatching { action() }.exceptionOrNull()
            _state.update { it.copy(submitting = false, error = error?.let(::describe) ?: FormError()) }
        }
    }

    /** 几种常见错误落到对应的输入框下面。 */
    private fun describe(e: Throwable): FormError = when ((e as? ApiException)?.code) {
        ProblemCode.RegistrationClosed -> FormError(mapOf("inviteCode" to "需要对方给你的邀请码"))
        ProblemCode.InviteInvalid -> FormError(mapOf("inviteCode" to "邀请码无效或已过期"))
        ProblemCode.UsernameTaken -> FormError(mapOf("username" to "这个用户名已经有人用了"))
        ProblemCode.RoomFull -> FormError(mapOf("inviteCode" to "这个房间已经有两个人了"))
        ProblemCode.Unauthorized -> FormError(message = "用户名或密码不正确")
        ProblemCode.RateLimited -> FormError(message = "尝试次数太多，请过一会儿再试")
        else -> e.toFormError()
    }
}
