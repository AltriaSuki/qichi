package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.DisplaySettings
import app.qichi.core.data.DisplaySettingsStore
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.markCharOf
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.navigation.Page
import app.qichi.shared.api.Member
import app.qichi.shared.api.Room
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

data class MeState(
    val room: Room? = null,
    val me: Member? = null,
    val fallbackName: String = "",
    val display: DisplaySettings = DisplaySettings(),
) {
    val displayName: String get() = me?.displayName ?: fallbackName
    val person: Person get() = if (room != null && me != null && room.createdBy == me.userId) Person.A else Person.B
}

@HiltViewModel(assistedFactory = MeViewModel.Factory::class)
class MeViewModel @AssistedInject constructor(
    @Assisted roomId: UUID,
    rooms: RoomRepository,
    session: SessionManager,
    display: DisplaySettingsStore,
) : ViewModel() {
    val state: StateFlow<MeState> = combine(
        rooms.observeRoom(roomId), rooms.observeMembers(roomId), rooms.me, display.settings,
    ) { room, members, me, settings ->
        MeState(room, members.firstOrNull { it.userId == session.currentUserId }, me?.user?.displayName.orEmpty(), settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeState())

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): MeViewModel
    }
}

/** 「我的」页，按 Me.dc.html：头像与名字、内容 / 房间 / 账号三组入口、页脚一句里尔克。 */
@Composable
fun MeScreen(
    roomId: UUID,
    onOpen: (Page) -> Unit,
    viewModel: MeViewModel = hiltViewModel<MeViewModel, MeViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.padding(start = Spacing.page, end = Spacing.page, top = Spacing.xxl, bottom = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PersonMark(markCharOf(state.displayName), state.person, size = 60.dp)
            Column {
                Text(
                    state.displayName,
                    style = type.feeling.copy(fontSize = 28.tsp, letterSpacing = 0.3.em, lineHeight = 39.tsp, color = colors.ink),
                    modifier = Modifier.semantics { heading() },
                )
                Text(state.room?.name.orEmpty(), style = type.caption.copy(letterSpacing = 0.2.em, color = colors.muted))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            MeSection("内容", listOf(Page.MyContent to null, Page.AiUsage to null), onOpen)
            MeSection("房间", listOf(Page.Members to null, Page.RoomSettings to null, Page.Trash to null), onOpen)
            MeSection(
                "账号",
                listOf(Page.Profile to null, Page.Display to state.display.textSizeLabel, Page.Notifications to null, Page.AiPrefs to null, Page.Security to null),
                onOpen,
            )
            if (app.qichi.BuildConfig.DEBUG) MeSection("开发", listOf(Page.Showcase to null), onOpen)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.m),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "两份孤独，彼此守护，彼此为界，彼此致意。",
                    style = type.caption.copy(letterSpacing = 0.12.em, lineHeight = 26.tsp, color = colors.muted),
                    textAlign = TextAlign.Center,
                )
                Text("Rilke", style = type.numeral.copy(fontSize = 15.tsp, letterSpacing = 0.04.em, color = colors.muted))
                // 版本与检查更新（有新版本时弹出的对话框在 QichiRoot 里）
                val update: app.qichi.core.update.UpdateViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
                val checking = update.state.collectAsStateWithLifecycle().value is app.qichi.core.update.UpdateState.Checking
                Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("版本 ${app.qichi.BuildConfig.VERSION_NAME}", style = type.caption.copy(color = colors.faint))
                    app.qichi.core.designsystem.component.TextAction(if (checking) "正在检查…" else "检查更新", { update.check() }, enabled = !checking, color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun MeSection(title: String, rows: List<Pair<Page, String?>>, onOpen: (Page) -> Unit) {
    Column {
        SectionLabel(title)
        Column(Modifier.padding(top = 0.dp)) {
            rows.forEach { (page, trailing) -> MeRow(page.title, trailing) { onOpen(page) } }
        }
    }
}

/** 入口行：文字 + 右侧可选的当前值 + 细箭头。 */
@Composable
fun MeRow(title: String, trailing: String? = null, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRow)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.bodyLarge.copy(letterSpacing = 0.08.em, color = colors.ink), modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(end = Spacing.xs))
        }
        Icon(QichiIcons.Forward, contentDescription = null, tint = colors.faint, modifier = Modifier.size(14.dp))
    }
}
