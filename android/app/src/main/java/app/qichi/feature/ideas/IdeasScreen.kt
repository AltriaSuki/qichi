package app.qichi.feature.ideas

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TagChip
import app.qichi.core.designsystem.component.TagFilterRow
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Idea
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.Tags
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * 「一起 → 灵感」（按 New-Ideas）：两列便签（像钉在软木板上，轻轻歪着、有的贴胶带），顶部按 #标签 筛选。
 * 点自己的灵感可以修改；长按可以删除（两个人都可以，进回收站）。
 */
@Composable
fun IdeasScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpenTags: () -> Unit = {},
    vm: IdeasViewModel = hiltViewModel<IdeasViewModel, IdeasViewModel.Factory>(key = "ideas-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var writing by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Idea?>(null) }
    var deleting by remember { mutableStateOf<Idea?>(null) }

    val tags = remember(state.ideas) { topTags(state.ideas.map { it.value.body }) }
    val shown = state.ideas.filter { local ->
        val body = local.value.body
        (filter == null || Tags.has(body, filter!!)) && (query.isBlank() || body.contains(query.trim(), ignoreCase = true))
    }

    Box(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(
                Feature.Ideas, onBack,
                actions = listOf(
                    BarAction("标签", QichiIcons.Tag, onOpenTags),
                    BarAction(if (searching) "收起搜索" else "搜索", QichiIcons.Search, { searching = !searching; if (!searching) query = "" }),
                ),
            )
            if (searching) {
                QichiTextField(query, { query = it }, label = "搜索", placeholder = "灵感里的字", modifier = Modifier.padding(horizontal = Spacing.page).padding(bottom = Spacing.s))
            }
            if (tags.isNotEmpty()) TagFilterRow(tags, filter, { filter = it })
            if (state.loaded && state.ideas.isEmpty()) {
                EmptyIdeas(Modifier.weight(1f))
            } else {
                IdeaBoard(
                    shown, state.people, state.zone, Modifier.weight(1f),
                    onClick = { if (it.authorId == state.people.myUserId) editing = it },
                    onLongPress = { deleting = it },
                )
            }
        }
        Fab("记一条", { writing = true })
    }

    if (writing) {
        var text by rememberSaveable { mutableStateOf(filter?.let { " #$it" } ?: "") }
        AlertDialog(
            onDismissRequest = { writing = false },
            containerColor = colors.paper,
            title = { Text("记一条", style = QichiTheme.typography.barTitle.copy(color = colors.ink)) },
            text = { QichiTextField(text, { text = it.take(Limits.IDEA_BODY_LENGTH.last) }, label = "一个念头，可以带 #标签", singleLine = false) },
            confirmButton = { TextAction("记下", { vm.add(text); writing = false }, enabled = text.isNotBlank()) },
            dismissButton = { TextAction("取消", { writing = false }, color = colors.muted) },
        )
    }
    editing?.let { idea ->
        var text by remember(idea.id) { mutableStateOf(idea.body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = colors.paper,
            title = { Text("改一下", style = QichiTheme.typography.barTitle.copy(color = colors.ink)) },
            text = { QichiTextField(text, { text = it.take(Limits.IDEA_BODY_LENGTH.last) }, label = "灵感", singleLine = false) },
            confirmButton = { TextAction("保存", { vm.edit(idea, text); editing = null }, enabled = text.isNotBlank()) },
            dismissButton = { TextAction("取消", { editing = null }, color = colors.muted) },
        )
    }
    deleting?.let { idea ->
        ConfirmDialog("删除这条灵感？", "会进回收站，可以恢复。", "删除", onConfirm = { vm.delete(idea) }, onDismiss = { deleting = null })
    }
}

/** 两列便签：第二列往下错开一点；每张的颜色、倾斜、胶带按位置轮换。 */
@Composable
private fun IdeaBoard(ideas: List<Local<Idea>>, people: People, zone: ZoneId, modifier: Modifier, onClick: (Idea) -> Unit, onLongPress: (Idea) -> Unit) {
    val left = ideas.filterIndexed { i, _ -> i % 2 == 0 }
    val right = ideas.filterIndexed { i, _ -> i % 2 == 1 }
    Row(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            left.forEachIndexed { i, idea -> IdeaNote(idea, people, zone, i * 2, onClick, onLongPress) }
            Spacer(Modifier.height(FabClearance))
        }
        Column(Modifier.weight(1f).padding(top = Spacing.ml), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            right.forEachIndexed { i, idea -> IdeaNote(idea, people, zone, i * 2 + 1, onClick, onLongPress) }
            Spacer(Modifier.height(FabClearance))
        }
    }
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun IdeaNote(local: Local<Idea>, people: People, zone: ZoneId, index: Int, onClick: (Idea) -> Unit, onLongPress: (Idea) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val idea = local.value
    val tint = listOf(colors.personA, colors.personB, colors.accent, null, colors.personB, colors.personA)[index % 6]
    val tape = listOf(null, colors.personB, colors.accent, null, colors.personA, null)[index % 6]
    val rotation = listOf(-1.2f, 1f, .8f, -.8f, -1f, .6f)[index % 6]
    val shape = RoundedCornerShape(12.dp)
    val tags = Tags.parse(idea.body)
    val text = Tags.strip(idea.body).ifBlank { idea.body }
    Box(Modifier.rotate(rotation)) {
        Column(
            Modifier.fillMaxWidth().lift(colors, shape).clip(shape).background(colors.card)
                .then(if (tint != null) Modifier.background(tint.copy(alpha = .1f)) else Modifier)
                .combinedClickable(role = Role.Button, onClick = { onClick(idea) }, onLongClickLabel = "删除", onLongClick = { onLongPress(idea) })
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Text(text, style = type.body.copy(lineHeight = 24.75.tsp, color = colors.ink))
            if (tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { TagChip(it) }
                }
            }
            Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PersonMark(people.markChar(idea.authorId), people.person(idea.authorId), size = 18.dp)
                Text(idea.createdAt.atZone(zone).format(MD) + if (local.isPending) " · 待发送" else "", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
            }
        }
        if (tape != null) Tape(Modifier.align(Alignment.TopCenter).offset(y = (-8).dp), color = tape, width = 40.dp, rotation = rotation * 5)
    }
}

/** 空的时候（按 New-Ideas-Empty）：两张叠着的便签、一句手写、一枝绿萝和一颗星。 */
@Composable
private fun EmptyIdeas(modifier: Modifier) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.page), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(220.dp, 170.dp).clearAndSetSemantics { }) {
            Box(Modifier.offset(x = 36.dp, y = 30.dp).rotate(-6f).size(120.dp).lift(colors, RoundedCornerShape(12.dp)).background(colors.card, RoundedCornerShape(12.dp)).background(colors.accent.copy(alpha = .1f), RoundedCornerShape(12.dp)))
            Box(Modifier.offset(x = 70.dp, y = 20.dp).rotate(4f).size(124.dp).lift(colors, RoundedCornerShape(12.dp)).background(colors.card, RoundedCornerShape(12.dp))) {
                HandNote("一个念头……", Modifier.padding(start = 12.dp, top = 44.dp), fontSizeSp = 20f, rotation = -4f)
                Tape(Modifier.offset(x = 36.dp, y = (-8).dp), color = colors.personB, width = 46.dp, rotation = -8f)
            }
            Sprig(Modifier.offset(x = (-16).dp, y = 120.dp).rotate(-12f), width = 130.dp)
            Icon(QichiIcons.Spark, contentDescription = null, tint = colors.accent, modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 4.dp).size(22.dp))
        }
        Text("还没有灵感", style = type.headline.copy(fontSize = 18.tsp, fontWeight = FontWeight.W600, color = colors.ink), modifier = Modifier.padding(top = Spacing.ml))
        Row(Modifier.padding(top = 4.dp, bottom = FabClearance), verticalAlignment = Alignment.CenterVertically) {
            Text("想到什么就记一条，带上 ", style = type.caption.copy(fontSize = 14.tsp, color = colors.muted))
            TagChip("标签")
            Text(" 以后好找", style = type.caption.copy(fontSize = 14.tsp, color = colors.muted))
        }
    }
}
