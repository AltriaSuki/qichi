package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.ui.toFormError
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.rules.Limits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileState(
    val username: String = "",
    val savedName: String = "",
    val name: String = "",
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !saving && name.trim() != savedName && name.trim().length in Limits.DISPLAY_NAME_LENGTH
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val rooms: RoomRepository,
    private val session: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            rooms.me.first()?.user?.let { u ->
                _state.update { it.copy(username = u.username, savedName = u.displayName, name = u.displayName) }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v, error = null) }

    /** 改显示名需要联网（两个人的房间里都会看到新名字）。 */
    fun save() {
        val name = _state.value.name.trim()
        if (!_state.value.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { rooms.updateMe(UpdateMeRequest(displayName = Patch.of(name))) }
                .onSuccess { me -> _state.update { it.copy(saving = false, savedName = me.user.displayName, name = me.user.displayName) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.toFormError().message) } }
        }
    }

    fun logout() = viewModelScope.launch { session.logout() }
}

@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        BackBar(title = "资料", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            QichiTextField(
                value = state.name, onValueChange = viewModel::onName, label = "显示名",
                error = state.error,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.save() }),
            )
            PrimaryButton(if (state.saving) "保存中" else "保存", onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth())
            Column {
                SectionLabel("用户名")
                Text(state.username, style = type.bodyLarge.copy(color = colors.ink))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                TextAction("退出登录", onClick = { viewModel.logout() }, color = colors.accent)
            }
        }
    }
}
