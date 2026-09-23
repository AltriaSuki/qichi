package app.qichi.feature.timeline

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.chinese
import app.qichi.core.ui.monthRoman
import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.model.TimelineEntryKind
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.time.YearMonth
import java.util.UUID

private val chineseMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")

private val TimelineEntryKind.label: String
    get() = when (this) {
        TimelineEntryKind.Decision -> "定下"
        TimelineEntryKind.Idea -> "灵感"
        TimelineEntryKind.Plan -> "完成"
        TimelineEntryKind.Photo -> "照片"
    }

/**
 * 共同时间线：按月回看定下的决定、灵感、完成的计划、两人都选中的照片（服务端拼装，需要联网）。
 * 「选照片」里从聊天发过的照片中各自挑，两人都选中的才出现在时间线上。
 */
@Composable
fun TimelineScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (TimelineEntry) -> Unit,
    vm: TimelineViewModel = hiltViewModel<TimelineViewModel, TimelineViewModel.Factory>(key = "timeline-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }

    if (picking) {
        PhotoPicker(state, vm, onBack = { picking = false; vm.load(null) })
        return
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("时间线", onBack) { TextAction("选照片", { picking = true; vm.loadPicks() }) }
        val page = state.page
        when {
            page == null && state.failed -> Column(Modifier.padding(Spacing.page)) {
                Text("时间线要联网才能看。", style = type.body.copy(color = colors.muted))
                TextAction("再试一次", { vm.load(null) })
            }
            page == null -> Text("正在翻……", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(Spacing.page))
            else -> {
                // ── 月份 ──
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(QichiIcons.ChevronLeft, "更早", { state.older?.let(vm::load) }, enabled = state.older != null,
                        tint = if (state.older != null) colors.ink else colors.faint)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(chineseMonths[page.month - 1], style = type.pageTitle.copy(fontSize = 22.tsp, color = colors.ink), modifier = Modifier.semantics { heading() })
                        Text(page.year.toString(), style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted))
                    }
                    IconAction(QichiIcons.ChevronRight, "更晚", { state.newer?.let(vm::load) }, enabled = state.newer != null,
                        tint = if (state.newer != null) colors.ink else colors.faint)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
                    if (page.entries.isEmpty()) {
                        Text(
                            if (page.months.isEmpty()) "还没有可以回看的事。定下的决定、记下的灵感、完成的计划，还有两个人都选中的照片，都会慢慢出现在这里。"
                            else "这个月没有记下什么。",
                            style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m),
                        )
                    }
                    page.entries.groupBy { it.at.atZone(state.zone).toLocalDate() }.forEach { (day, entries) ->
                        Text("${day.dayOfMonth} · ${monthRoman(day.monthValue)}  ${day.dayOfWeek.chinese}",
                            style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted), modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.xs))
                        entries.forEach { e -> EntryRow(e, state, vm, onClick = { onOpen(e) }) }
                    }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: TimelineEntry, state: TimelineState, vm: TimelineViewModel, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    Column(
        Modifier.fillMaxWidth()
            .then(if (e.kind == TimelineEntryKind.Photo) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick))
            .padding(vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(e.kind.label, style = type.caption.copy(color = colors.accent))
            e.authorId?.let { PersonMark(people.markChar(it), people.person(it), size = 16.dp) }
        }
        if (e.kind == TimelineEntryKind.Photo) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(vm.urls.thumbnail(e.refId)).crossfade(!QichiTheme.reduceMotion).build(),
                contentDescription = "两个人都选中的照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(top = Spacing.xs).fillMaxWidth().aspectRatio(4f / 3f).clip(QichiShapes.card).background(colors.surface),
            )
        } else {
            Text(e.title, style = type.bodyLarge.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            e.detail?.takeIf { it.isNotBlank() }?.let {
                Text(if (e.kind == TimelineEntryKind.Decision) "→ $it" else it, style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 选照片：聊天发过的照片，点一下选中 / 取消；对方选中的有小标记，两人都选中的会出现在时间线上。 */
@Composable
private fun PhotoPicker(state: TimelineState, vm: TimelineViewModel, onBack: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val me = people.myUserId
    val partner = people.partner
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("选照片", onBack)
        Text("点一下选中。两个人都选中的照片，会出现在时间线上。", style = type.caption.copy(color = colors.muted),
            modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.xs))
        if (state.photos.isEmpty()) {
            Text("聊天里还没有照片。", style = type.body.copy(color = colors.muted), modifier = Modifier.padding(Spacing.page))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.photos, key = { it.id }) { photo ->
                val users = state.picks[photo.id].orEmpty()
                val mine = me != null && me in users
                val theirs = partner != null && partner.userId in users
                Box(
                    Modifier.aspectRatio(1f).clip(QichiShapes.card)
                        .clickable(enabled = state.picksLoaded, role = Role.Checkbox) { vm.togglePick(photo.id) }
                        .semantics {
                            selected = mine
                            contentDescription = buildString {
                                append("照片")
                                if (mine) append("，我选中了")
                                if (theirs) append("，${partner!!.displayName}选中了")
                            }
                        },
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(vm.urls.thumbnail(photo.id, 400)).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(colors.surface),
                    )
                    if (mine && theirs) Box(Modifier.fillMaxSize().border(3.dp, colors.accent, QichiShapes.card))
                    Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (theirs) PersonMark(people.markChar(partner!!.userId), people.person(partner.userId), size = 18.dp)
                        Box(
                            Modifier.size(20.dp).clip(CircleShape)
                                .background(if (mine) colors.accent else colors.background.copy(alpha = 0.6f))
                                .border(1.dp, colors.paper, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (mine) Text("✓", style = type.caption.copy(fontSize = 11.tsp, color = colors.paper))
                        }
                    }
                }
            }
        }
    }
}
