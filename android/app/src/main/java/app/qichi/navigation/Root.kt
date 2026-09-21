package app.qichi.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.TextAction
import app.qichi.feature.auth.AuthFlow
import app.qichi.feature.room.MembersScreen
import app.qichi.feature.room.RoomSetupScreen
import app.qichi.shared.api.Me
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 当前房间 id：房间内的页面都从这里取。 */
val LocalRoomId = staticCompositionLocalOf<UUID> { error("没有当前房间") }

sealed interface RootState {
    data object Loading : RootState
    data object LoggedOut : RootState

    /** 已登录但读不到「我」（第一次打开且离线） */
    data object Unavailable : RootState

    /** 已登录，还没有房间 */
    data object NeedsRoom : RootState
    data class Ready(val roomId: UUID, val me: Me) : RootState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val session: SessionManager,
    private val rooms: RoomRepository,
) : ViewModel() {
    private val refreshFailed = MutableStateFlow(false)

    val state: StateFlow<RootState> = combine(session.state, rooms.me, rooms.currentRoomId, refreshFailed) { s, me, current, failed ->
        when (s) {
            SessionState.Loading -> RootState.Loading
            SessionState.LoggedOut -> RootState.LoggedOut
            is SessionState.LoggedIn -> when {
                me == null || me.user.id != s.userId -> if (failed) RootState.Unavailable else RootState.Loading
                me.rooms.isEmpty() -> RootState.NeedsRoom
                else -> RootState.Ready(current ?: me.rooms.first().roomId, me)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    init {
        viewModelScope.launch {
            session.state.collectLatest { if (it is SessionState.LoggedIn) refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshFailed.value = runCatching { rooms.refreshMe() }.isFailure
        }
    }

    fun logout() = viewModelScope.launch { session.logout() }
}

/** App 的最外层：按登录与房间状态决定显示欢迎页、建房页，还是主界面。 */
@Composable
fun QichiRoot(
    pendingLink: DeepLink?,
    onLinkHandled: () -> Unit,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 刚建好房间时先带去「成员与邀请」生成邀请码
    var showInviteFor by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        RootState.Loading -> Box(Modifier.fillMaxSize().background(QichiTheme.colors.background))
        RootState.LoggedOut -> AuthFlow()
        RootState.Unavailable -> UnavailableScreen(onRetry = viewModel::refresh, onLogout = { viewModel.logout() })
        RootState.NeedsRoom -> RoomSetupScreen(onCreated = { showInviteFor = "new" })
        is RootState.Ready -> CompositionLocalProvider(LocalRoomId provides s.roomId) {
            if (showInviteFor != null) {
                MembersScreen(roomId = s.roomId, onBack = { showInviteFor = null })
            } else {
                QichiApp(pendingLink = pendingLink, onLinkHandled = onLinkHandled)
            }
        }
    }
}

@Composable
private fun UnavailableScreen(onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(QichiTheme.colors.background)
            .padding(Spacing.page),
        verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("连不上服务器", style = QichiTheme.typography.pageTitle.copy(color = QichiTheme.colors.ink))
        PrimaryButton("再试一次", onClick = onRetry)
        TextAction("退出登录", onClick = onLogout, color = QichiTheme.colors.muted)
    }
}
