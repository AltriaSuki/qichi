package app.qichi.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.ExpiredSession
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SignInResult
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
    /** 忘了密码时对方给的重置码（P16-04） */
    val resetCode: String = "",
    val submitting: Boolean = false,
    val error: FormError = FormError(),
    /** 登录被动失效、本机内容还留着时的提示（P13-08） */
    val expiredNotice: String? = null,
    /** 换账号前要确认：本机还有上一个账号这么多条没发出去的内容 */
    val confirmSwitch: Int? = null,
)

/** 登录与注册共用的表单状态。成功后 SessionManager 的状态变化会把界面切走。 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var pendingSwitch: SignInResult.NeedsConfirm? = null

    init {
        viewModelScope.launch {
            session.expired.collect { expired -> _state.update { it.copy(expiredNotice = expired?.let(::expiredText)) } }
        }
    }

    private fun expiredText(e: ExpiredSession): String =
        if (e.unsent > 0) "登录已失效，请重新登录。还有 ${e.unsent} 条内容没发出去，登录同一个账号后会接着发。" else "登录已失效，请重新登录。"

    fun onUsername(v: String) = _state.update { it.copy(username = v.lowercase().filter { c -> !c.isWhitespace() }, error = FormError()) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = FormError()) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v, error = FormError()) }
    fun onInviteCode(v: String) = _state.update { it.copy(inviteCode = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(Limits.INVITE_LENGTH), error = FormError()) }

    fun onResetCode(v: String) = _state.update { it.copy(resetCode = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(Limits.INVITE_LENGTH), error = FormError()) }

    /** 忘了密码：用户名 + 对方给的重置码 + 新密码，成功后直接登录（P16-04）。 */
    fun resetPassword() {
        val s = _state.value
        val fields = buildMap {
            if (s.username.isBlank()) put("username", "请填写用户名")
            if (s.resetCode.length != Limits.INVITE_LENGTH) put("resetCode", "重置码是 8 位")
            if (s.password.length !in Limits.PASSWORD_LENGTH) put("password", "新密码至少 8 位")
        }
        if (fields.isNotEmpty()) return _state.update { it.copy(error = FormError(fields)) }
        resetting = true
        submit { session.resetPassword(s.username, s.resetCode, s.password) }
    }

    /** 这次提交是不是在重置密码（出错时的说法不一样） */
    private var resetting = false

    fun login() {
        resetting = false
        val s = _state.value
        val fields = buildMap {
            if (s.username.isBlank()) put("username", "请填写用户名")
            if (s.password.isEmpty()) put("password", "请填写密码")
        }
        if (fields.isNotEmpty()) return _state.update { it.copy(error = FormError(fields)) }
        submit { session.login(s.username, s.password) }
    }

    fun register() {
        resetting = false
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

    /** 确认换账号：清掉上一个账号没发出去的内容，登录新账号。 */
    fun confirmSwitch() {
        val pending = pendingSwitch ?: return
        pendingSwitch = null
        _state.update { it.copy(confirmSwitch = null) }
        submit {
            session.confirmSwitch(pending)
            SignInResult.Done
        }
    }

    /** 不换了：刚登录的新账号作废，本机内容留着。 */
    fun cancelSwitch() {
        val pending = pendingSwitch ?: return
        pendingSwitch = null
        _state.update { it.copy(confirmSwitch = null) }
        viewModelScope.launch { session.cancelSwitch(pending) }
    }

    private fun submit(action: suspend () -> SignInResult) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = FormError()) }
        viewModelScope.launch {
            val result = runCatching { action() }
            val needsConfirm = result.getOrNull() as? SignInResult.NeedsConfirm
            pendingSwitch = needsConfirm
            _state.update {
                it.copy(
                    submitting = false,
                    error = result.exceptionOrNull()?.let(::describe) ?: FormError(),
                    confirmSwitch = needsConfirm?.unsent,
                )
            }
        }
    }

    /** 几种常见错误落到对应的输入框下面。 */
    private fun describe(e: Throwable): FormError = when ((e as? ApiException)?.code) {
        ProblemCode.RegistrationClosed -> FormError(mapOf("inviteCode" to "需要对方给你的邀请码"))
        ProblemCode.InviteInvalid -> FormError(mapOf("inviteCode" to "邀请码无效或已过期"))
        ProblemCode.UsernameTaken -> FormError(mapOf("username" to "这个用户名已经有人用了"))
        ProblemCode.RoomFull -> FormError(mapOf("inviteCode" to "这个房间已经有两个人了"))
        ProblemCode.Unauthorized ->
            if (resetting) FormError(mapOf("resetCode" to "重置码不对或已经过期，请对方再生成一个")) else FormError(message = "用户名或密码不正确")
        ProblemCode.RateLimited -> FormError(message = "尝试次数太多，请过一会儿再试")
        else -> e.toFormError()
    }
}
