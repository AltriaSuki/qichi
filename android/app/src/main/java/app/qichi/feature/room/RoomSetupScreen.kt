package app.qichi.feature.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiKeyboard
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction
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

data class RoomSetupState(
    val roomName: String = "",
    val inviteCode: String = "",
    val busy: Boolean = false,
    val createError: FormError = FormError(),
    val joinError: FormError = FormError(),
)

/** 登录后还没有房间：新建一个，或者用对方给的邀请码加入。 */
@HiltViewModel
class RoomSetupViewModel @Inject constructor(
    private val rooms: RoomRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(RoomSetupState())
    val state: StateFlow<RoomSetupState> = _state.asStateFlow()

    fun onRoomName(v: String) = _state.update { it.copy(roomName = v, createError = FormError()) }
    fun onInviteCode(v: String) = _state.update {
        it.copy(inviteCode = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(Limits.INVITE_LENGTH), joinError = FormError())
    }

    fun create(onCreated: () -> Unit) {
        val name = _state.value.roomName.trim()
        if (name.length !in Limits.ROOM_NAME_LENGTH) {
            return _state.update { it.copy(createError = FormError(mapOf("name" to "房间名 1–40 个字"))) }
        }
        run(onError = { e -> _state.update { it.copy(createError = e.toFormError()) } }) {
            rooms.createRoom(name, anniversary = null)
            onCreated()
        }
    }

    fun join() {
        val code = _state.value.inviteCode
        if (code.length != Limits.INVITE_LENGTH) {
            return _state.update { it.copy(joinError = FormError(mapOf("code" to "邀请码是 8 位"))) }
        }
        run(onError = { e ->
            val error = when ((e as? ApiException)?.code) {
                ProblemCode.InviteInvalid -> FormError(mapOf("code" to "邀请码无效或已过期"))
                ProblemCode.RoomFull -> FormError(mapOf("code" to "这个房间已经有两个人了"))
                else -> e.toFormError()
            }
            _state.update { it.copy(joinError = error) }
        }) {
            rooms.acceptInvite(code)
        }
    }

    fun logout() = viewModelScope.launch { session.logout() }

    private fun run(onError: (Throwable) -> Unit, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure(onError)
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun RoomSetupScreen(onCreated: () -> Unit, viewModel: RoomSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(
            text = "屋檐",
            style = QichiTheme.typography.hubTitle.copy(color = colors.ink),
            modifier = Modifier
                .padding(top = 56.dp, bottom = Spacing.m)
                .semantics { heading() },
        )

        QichiTextField(
            value = state.roomName, onValueChange = viewModel::onRoomName, label = "新建一个房间",
            placeholder = "两个人的屋檐", error = state.createError["name"] ?: state.createError.message,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.create(onCreated) }),
        )
        PrimaryButton("建好", onClick = { viewModel.create(onCreated) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(Spacing.xl))

        QichiTextField(
            value = state.inviteCode, onValueChange = viewModel::onInviteCode, label = "用邀请码加入",
            placeholder = "K7PQ2XRM", error = state.joinError["code"] ?: state.joinError.message,
            keyboardOptions = QichiKeyboard.code.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.join() }),
        )
        PrimaryButton("加入", onClick = viewModel::join, enabled = !state.busy, modifier = Modifier.fillMaxWidth())

        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            TextAction("退出登录", onClick = { viewModel.logout() }, color = colors.muted)
        }
    }
}
