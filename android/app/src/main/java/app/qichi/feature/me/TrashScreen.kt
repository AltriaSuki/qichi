package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TrashEntry
import app.qichi.core.data.TrashRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.feelingWord
import app.qichi.shared.api.Event
import app.qichi.shared.api.Message
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.TrashType
import app.qichi.shared.rules.MessageRules
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class TrashState(
    val people: People = People.Empty,
    val entries: List<TrashEntry> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = TrashViewModel.Factory::class)
class TrashViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val trash: TrashRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    val state: StateFlow<TrashState> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId), trash.observe(roomId)) { room, members, entries ->
        TrashState(People(room, members, session.currentUserId), entries, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashState())

    init {
        // 在线时补齐本机没有的旧条目；离线就只看本机的
        viewModelScope.launch {
            try {
                trash.refresh(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun restore(entry: TrashEntry) = viewModelScope.launch { trash.restore(roomId, entry) }

    fun purge(entry: TrashEntry) = viewModelScope.launch { trash.purge(roomId, entry) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TrashViewModel
    }
}

private val DELETED_AT = DateTimeFormatter.ofPattern("M · d  HH:mm")

/** 「我的 → 回收站」（设计稿没有这一页，按列表页的规则做）：最近删除的在前，可以恢复或彻底删除。 */
@Composable
fun TrashScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel<TrashViewModel, TrashViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var purging by remember { mutableStateOf<TrashEntry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        BackBar(title = "回收站", onBack = onBack)
        if (state.loaded && state.entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                Text("回收站是空的", style = type.caption.copy(color = colors.muted))
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, bottom = 32.dp)) {
            item {
                Text(
                    "删除的消息、心情、待办和日程会留在这里，恢复后回到原来的样子。",
                    style = type.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(bottom = Spacing.m),
                )
            }
            items(state.entries, key = { "${it.type}-${it.id}" }) { entry ->
                TrashRow(entry, state.people, onRestore = { viewModel.restore(entry) }, onPurge = { purging = entry })
            }
        }
    }

    purging?.let { entry ->
        ConfirmDialog(
            title = "彻底删除？",
            text = "彻底删除后就找不回来了，对方的回收站里也会一起消失。",
            confirmLabel = "彻底删除",
            onConfirm = { viewModel.purge(entry) },
            onDismiss = { purging = null },
        )
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, people: People, onRestore: () -> Unit, onPurge: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(typeLabel(entry.type), style = type.caption.copy(fontSize = 12.tsp, color = colors.accent), modifier = Modifier.padding(top = 3.dp))
            Text(
                summary(entry, people),
                style = type.bodyLarge.copy(color = colors.ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${people.name(entry.deletedBy)} 删除于 ${DELETED_AT.format(entry.deletedAt.atZone(zone))}",
                style = type.caption.copy(color = colors.muted),
                modifier = Modifier.weight(1f),
            )
            TextAction("恢复", onClick = onRestore)
            TextAction("彻底删除", onClick = onPurge, color = colors.muted)
        }
    }
}

private fun typeLabel(type: TrashType): String = when (type) {
    TrashType.Message -> "消息"
    TrashType.Mood -> "心情"
    TrashType.Todo -> "待办"
    TrashType.Event -> "日程"
    TrashType.Question -> "问答"
    TrashType.Plan -> "计划"
    TrashType.Idea -> "灵感"
    TrashType.Document -> "文稿"
    TrashType.BoardTopic -> "留言主题"
    TrashType.BoardPost -> "留言"
    TrashType.ArchiveItem -> "档案"
    TrashType.Decision -> "决定"
    TrashType.Book -> "书"
    TrashType.Summary -> "总结"
    TrashType.PlanStage -> "计划的阶段"
    TrashType.Milestone -> "里程碑"
}

private fun summary(entry: TrashEntry, people: People): String = when (val e = entry.entity) {
    is Message -> {
        val content = e.body.ifBlank { MessageRules.replyExcerpt(e.kind, e.body, e.file?.fileName, e.retractedAt != null) ?: "（已撤回）" }
        "${if (e.kind == MessageKind.Ai) "AI" else people.name(e.authorId)}：$content"
    }
    is Mood -> listOfNotNull(feelingWord(e.label, e.intensity), e.note).joinToString("  ")
    is Todo -> e.title
    is Event -> e.title
    is app.qichi.shared.api.Question -> e.text
    is app.qichi.shared.api.Plan -> e.title
    is app.qichi.shared.api.Idea -> e.body
    is app.qichi.shared.api.Document -> e.title
    is app.qichi.shared.api.BoardTopic -> e.title
    is app.qichi.shared.api.BoardPost -> e.body
    is app.qichi.shared.api.ArchiveItem -> e.title
    is app.qichi.shared.api.Decision -> e.question
    is app.qichi.shared.api.Book -> e.title
    is app.qichi.shared.api.Summary -> "${e.rangeStart} — ${e.rangeEnd}"
    is app.qichi.shared.api.PlanStage -> e.title
    is app.qichi.shared.api.Milestone -> e.title
    else -> ""
}
