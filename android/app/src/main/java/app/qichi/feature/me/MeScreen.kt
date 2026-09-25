package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.DisplaySettings
import app.qichi.core.data.DisplaySettingsStore
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TrashRepository
import app.qichi.core.designsystem.FeatureTone
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.color
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.MainTopBar
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.markCharOf
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.navigation.Page
import app.qichi.shared.api.Member
import app.qichi.shared.api.Room
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MeState(
    val room: Room? = null,
    val me: Member? = null,
    val fallbackName: String = "",
    val display: DisplaySettings = DisplaySettings(),
    /** 房间里的人数（成员与邀请那一行右边） */
    val memberCount: Int = 0,
    /** 回收站里的条数 */
    val trashCount: Int = 0,
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
    trash: TrashRepository,
) : ViewModel() {
    val state: StateFlow<MeState> = combine(
        rooms.observeRoom(roomId), rooms.observeMembers(roomId), rooms.me, display.settings, trash.observe(roomId),
    ) { room, members, me, settings, trashed ->
        MeState(
            room, members.firstOrNull { it.userId == session.currentUserId }, me?.user?.displayName.orEmpty(), settings,
            memberCount = members.size, trashCount = trashed.size,
        )
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
            .background(colors.background),
    ) {
        MainTopBar(
            state.displayName,
            note = state.room?.name,
            // 头像：人物圆标，右下角盖一枚「栖迟」小印章
            side = {
                Box(Modifier.padding(bottom = 4.dp, end = 12.dp)) {
                    PersonMark(markCharOf(state.displayName), state.person, size = 56.dp)
                    Seal("栖迟", Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 8.dp), size = 28.dp, rotation = 8f)
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            MeSection("内容", listOf(Page.MyContent to null, Page.AiUsage to null), onOpen)
            MeSection(
                "房间",
                listOf(Page.Members to state.memberCount.takeIf { it > 0 }?.toString(), Page.RoomSettings to null, Page.Trash to state.trashCount.takeIf { it > 0 }?.toString()),
                onOpen,
            )
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
                HandNote("两份孤独，彼此守护，彼此为界，彼此致意。", fontSizeSp = 19f, rotation = 0f)
                Text("— Rilke", style = type.numeral.copy(fontSize = 12.tsp, color = colors.faint))
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

/** 「我的」各行的图标和颜色（按 New-Me）。 */
private fun Page.meTile(): Pair<ImageVector, FeatureTone> = when (this) {
    Page.MyContent -> QichiIcons.Pen to FeatureTone.PersonB
    Page.AiUsage, Page.AiPrefs -> QichiIcons.Spark to FeatureTone.PersonB
    Page.Members -> QichiIcons.People to FeatureTone.PersonA
    Page.RoomSettings -> QichiIcons.Rings to FeatureTone.PersonA
    Page.Trash -> QichiIcons.Archive to FeatureTone.Muted
    Page.Profile -> QichiIcons.User to FeatureTone.Accent
    Page.Display -> QichiIcons.Eye to FeatureTone.Accent
    Page.Notifications -> QichiIcons.Mail to FeatureTone.Accent
    Page.Security -> QichiIcons.Lock to FeatureTone.Muted
    else -> QichiIcons.Spark to FeatureTone.Muted
}

@Composable
private fun MeSection(title: String, rows: List<Pair<Page, String?>>, onOpen: (Page) -> Unit) {
    Column {
        SectionLabel(title)
        rows.forEachIndexed { i, (page, trailing) ->
            val (icon, tone) = page.meTile()
            MeRow(page.title, trailing, icon = icon, tint = tone.color, first = i == 0) { onOpen(page) }
        }
    }
}

/** 入口行：功能色块 + 文字 + 右侧可选的当前值（数字用等宽）+ 细箭头；行之间是虚线。 */
@Composable
fun MeRow(title: String, trailing: String? = null, icon: ImageVector? = null, tint: Color = QichiTheme.colors.muted, first: Boolean = true, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.dashedDivider(colors, atTop = true))
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (icon != null) FeatureTile(icon, tint, size = 30.dp)
        Text(title, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
        if (trailing != null) {
            val numeric = trailing.all { it.isDigit() }
            Text(trailing, style = if (numeric) type.numeral.copy(fontSize = 14.tsp, color = colors.muted) else type.caption.copy(fontSize = 14.tsp, color = colors.muted))
        }
        Icon(QichiIcons.ChevronRight, contentDescription = null, tint = colors.faint, modifier = Modifier.size(16.dp))
    }
}
