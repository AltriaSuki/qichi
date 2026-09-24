package app.qichi.feature.room

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.markCharOf
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.toFormError
import app.qichi.shared.api.Invite
import app.qichi.shared.api.Member
import app.qichi.shared.api.Room
import app.qichi.shared.model.MemberRole
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MembersState(
    val room: Room? = null,
    val members: List<Member> = emptyList(),
    val myUserId: UUID? = null,
    val invite: Invite? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isOwner: Boolean get() = members.any { it.userId == myUserId && it.role == MemberRole.Owner }
    val canInvite: Boolean get() = isOwner && members.size < Limits.MAX_ROOM_MEMBERS
}

/** 成员与邀请：看两个人是谁；房主在房间只有自己时生成邀请码并分享。 */
@HiltViewModel(assistedFactory = MembersViewModel.Factory::class)
class MembersViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val invite = MutableStateFlow<Invite?>(null)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<MembersState> = combine(
        rooms.observeRoom(roomId),
        rooms.observeMembers(roomId),
        invite,
        busy,
        error,
    ) { room, members, inv, b, err ->
        MembersState(room, members, session.currentUserId, inv, b, err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembersState(myUserId = session.currentUserId))

    fun generateInvite() {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { rooms.createInvite(roomId) }
                .onSuccess { invite.value = it }
                .onFailure { e -> error.value = e.toFormError().message ?: "生成失败" }
            busy.update { false }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): MembersViewModel
    }
}

private val expiryFormat = DateTimeFormatter.ofPattern("M 月 d 日")

@Composable
fun MembersScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: MembersViewModel = hiltViewModel<MembersViewModel, MembersViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ItemTopBar("成员与邀请", onBack, feature = Feature.Me)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            Column {
                SectionLabel(state.room?.name ?: "房间")
                state.members.forEach { member ->
                    val person = if (member.userId == state.room?.createdBy) Person.A else Person.B
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.listRowTall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    ) {
                        PersonMark(markCharOf(member.displayName), person, size = 26.dp)
                        Text(member.displayName, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
                        Text(
                            member.joinedAt.atZone(ZoneId.systemDefault()).toLocalDate().let { "${it.monthValue} · ${it.dayOfMonth}" },
                            style = type.numeral.copy(color = colors.muted),
                        )
                    }
                }
            }

            if (state.canInvite) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    SectionLabel("邀请")
                    val invite = state.invite
                    if (invite == null) {
                        PrimaryButton(
                            if (state.busy) "生成中" else "生成邀请码",
                            onClick = viewModel::generateInvite,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = invite.code,
                            // 邀请码要照着输入：用正体、等高数字（lnum），避免旧式数字的高低错落
                            style = type.numeral.copy(
                                fontSize = 40.tsp, letterSpacing = 0.18.em, color = colors.ink,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Normal, fontFeatureSettings = "lnum",
                            ),
                        )
                        val until = invite.expiresAt.atZone(ZoneId.systemDefault()).format(expiryFormat)
                        Text("${until}前有效", style = type.caption.copy(color = colors.muted))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                            Pill("分享", onClick = {
                                val text = "来栖迟找我：邀请码 ${invite.code}（$until 前有效）"
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                context.startActivity(Intent.createChooser(send, "分享邀请码"))
                            })
                            TextAction("换一个", onClick = viewModel::generateInvite, color = colors.muted)
                        }
                    }
                    state.error?.let { Text(it, style = type.caption.copy(color = colors.accent)) }
                }
            }
        }
    }
}
