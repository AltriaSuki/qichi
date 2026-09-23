package app.qichi.feature.ideas

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.monthRoman
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Idea
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class IdeasState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val ideas: List<Local<Idea>> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = IdeasViewModel.Factory::class)
class IdeasViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val ideas: IdeaRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    val state: StateFlow<IdeasState> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), ideas.observeIdeas(roomId)) { room, members, all ->
        val zone = zoneOf(room?.timezone)
        IdeasState(People(room, members, session.currentUserId), zone, todayIn(zone), all, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdeasState())

    fun add(text: String) = viewModelScope.launch { ideas.add(roomId, text) }
    fun edit(idea: Idea, text: String) = viewModelScope.launch { ideas.edit(idea, text) }
    fun delete(idea: Idea) = viewModelScope.launch { ideas.delete(idea) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): IdeasViewModel
    }
}

/**
 * 「一起 → 灵感」：随手记下的想法，最新的在前。设计稿没有这一页，按列表页的规则做。
 * 点自己的灵感可以修改；长按可以删除（两个人都可以，进回收站）。
 */
@Composable
fun IdeasScreen(
    roomId: UUID,
    onBack: () -> Unit,
    vm: IdeasViewModel = hiltViewModel<IdeasViewModel, IdeasViewModel.Factory>(key = "ideas-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Idea?>(null) }
    var deleting by remember { mutableStateOf<Idea?>(null) }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        BackBar("灵感", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, bottom = Spacing.l)) {
            if (state.loaded && state.ideas.isEmpty()) {
                item { Text("想到什么就记下来，不用想清楚。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.m)) }
            }
            items(state.ideas, key = { it.value.id }) { local ->
                IdeaRow(local, state.people, state.zone, state.today,
                    onClick = { if (local.value.authorId == state.people.myUserId) editing = local.value },
                    onLongPress = { deleting = local.value })
            }
        }
        QuickInput(draft, { draft = it.take(Limits.IDEA_BODY_LENGTH.last) }, placeholder = "记一个灵感", actionLabel = "记下",
            onSubmit = { vm.add(draft); draft = "" },
            modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.s))
    }

    editing?.let { idea ->
        var text by remember(idea.id) { mutableStateOf(idea.body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = colors.paper,
            title = { Text("改一下", style = type.pageTitle.copy(color = colors.ink)) },
            text = { QichiTextField(text, { text = it.take(Limits.IDEA_BODY_LENGTH.last) }, label = "灵感", singleLine = false) },
            confirmButton = { TextAction("保存", { vm.edit(idea, text); editing = null }, enabled = text.isNotBlank()) },
            dismissButton = { TextAction("取消", { editing = null }, color = colors.muted) },
        )
    }
    deleting?.let { idea ->
        ConfirmDialog("删除这条灵感？", "会进回收站，可以恢复。", "删除", onConfirm = { vm.delete(idea) }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdeaRow(local: Local<Idea>, people: People, zone: ZoneId, today: LocalDate, onClick: () -> Unit, onLongPress: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val idea = local.value
    val date = idea.createdAt.atZone(zone).toLocalDate()
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClickLabel = "删除", onLongClick = onLongPress)
            .padding(vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(idea.body, style = type.bodyLarge.copy(color = colors.ink))
            val label = if (date == today) "今天" else if (date.year == today.year) "${date.dayOfMonth} · ${monthRoman(date.monthValue)}" else "${date.dayOfMonth} · ${monthRoman(date.monthValue)} · ${date.year}"
            Text(label + if (local.isPending) "  · 待发送" else "",
                style = if (date == today) type.caption.copy(color = colors.muted) else type.numeral.copy(fontSize = 15.tsp, color = colors.muted),
                modifier = Modifier.padding(top = 2.dp))
        }
        PersonMark(people.markChar(idea.authorId), people.person(idea.authorId), size = 18.dp, modifier = Modifier.padding(top = 4.dp))
    }
}
