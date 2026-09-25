package app.qichi.feature.timeline

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.FeatureTone
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.color
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Polaroid
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.decor.Watermark
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.displayName
import app.qichi.core.ui.icon
import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.model.TimelineEntryKind
import app.qichi.shared.rules.Tags
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.util.UUID

private val chineseMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")

/** 每种事的小标题：图标、名字、颜色。 */
private data class KindLook(val icon: ImageVector, val label: String, val tone: FeatureTone)

private fun TimelineEntryKind.look(): KindLook = when (this) {
    TimelineEntryKind.Decision -> KindLook(QichiIcons.Sign, "决定", FeatureTone.Accent)
    TimelineEntryKind.Idea -> KindLook(QichiIcons.Idea, "灵感", FeatureTone.Accent)
    TimelineEntryKind.Plan -> KindLook(QichiIcons.Flag, "计划完成", FeatureTone.Accent)
    TimelineEntryKind.PlanProgress -> KindLook(QichiIcons.Flag, "计划", FeatureTone.Accent)
    TimelineEntryKind.Photo -> KindLook(QichiIcons.Image, "照片", FeatureTone.PersonA)
    TimelineEntryKind.Mood -> KindLook(QichiIcons.Mood, "心情", FeatureTone.PersonA)
    TimelineEntryKind.Qna -> KindLook(QichiIcons.Qna, "问答", FeatureTone.PersonB)
    TimelineEntryKind.Writing -> KindLook(QichiIcons.Pen, "写作", FeatureTone.PersonB)
}

private val weekdayShort = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 共同时间线（按 New-Timeline）：日记一样按天——心情、揭晓了的问答、决定、计划进展、灵感、文稿存档、两人都选中的照片
 * （服务端拼装，需要联网）。「按天 / 照片」两种看法；照片是拍立得。「选照片」里各自挑，两人都选中的才上来。
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
    var photosOnly by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }

    if (picking) {
        PhotoPicker(state, vm, onBack = { picking = false; vm.load(null) })
        return
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        FeatureTopBar(Feature.Timeline, onBack, actions = listOf(BarAction("选照片", QichiIcons.Image, { picking = true; vm.loadPicks() })))
        Segmented(listOf("按天", "照片"), if (photosOnly) 1 else 0, { photosOnly = it == 1 })
        val page = state.page
        when {
            page == null && state.failed -> Column(Modifier.padding(Spacing.page)) {
                Text("时间线要联网才能看。", style = type.body.copy(color = colors.muted))
                TextAction("再试一次", { vm.load(null) })
            }
            page == null -> TimelineLoading()
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.page, end = Spacing.page, top = Spacing.xs)) {
                // ── 月份：大号淡水印 + 九月 2026 + 前后翻 ──
                Box(Modifier.fillMaxWidth()) {
                    Watermark(
                        "%02d".format(page.month),
                        Modifier.align(Alignment.TopEnd).layout { m, c ->
                            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                            layout(p.width, 0) { p.place(0, (-44).dp.roundToPx()) }
                        },
                        fontSizeSp = 120f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chineseMonths[page.month - 1], style = type.headline.copy(fontSize = 20.tsp, color = colors.ink), modifier = Modifier.semantics { heading() })
                        Text(page.year.toString(), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted), modifier = Modifier.padding(start = Spacing.xs))
                        Spacer(Modifier.weight(1f))
                        IconAction(QichiIcons.ChevronLeft, "更早", { state.older?.let(vm::load) }, enabled = state.older != null)
                        IconAction(QichiIcons.ChevronRight, "更晚", { state.newer?.let(vm::load) }, enabled = state.newer != null)
                    }
                }
                val days = remember(page, state.zone) { timelineDays(page.entries, state.zone) }
                if (page.entries.isEmpty()) {
                    Text(
                        if (page.months.isEmpty()) "还没有可以回看的事。心情、问答、定下的决定、灵感、写的东西，还有两个人都选中的照片，都会慢慢出现在这里。"
                        else "这个月没有记下什么。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m),
                    )
                }
                if (photosOnly) {
                    PhotoWall(days.flatMap { it.photos }, vm)
                } else {
                    days.forEachIndexed { i, day -> DayBlock(day, state, vm, first = i == 0, onOpen = onOpen) }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

/** 一天：左边星期小字 + 等宽的日子（今天是深色圆），右边这一天的事。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayBlock(day: TimelineDay, state: TimelineState, vm: TimelineViewModel, first: Boolean, onOpen: (TimelineEntry) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.dashedDivider(colors, atTop = true)).padding(vertical = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(weekdayShort[day.date.dayOfWeek.value - 1], style = type.caption.copy(fontSize = 12.tsp, fontWeight = FontWeight.W500, color = colors.muted))
            if (day.date == state.today) {
                Box(Modifier.size(40.dp).background(colors.ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${day.date.dayOfMonth}", style = type.numeral.copy(fontSize = 20.tsp, color = colors.background))
                }
            } else {
                Text("${day.date.dayOfMonth}", style = type.numeral.copy(fontSize = 26.tsp, lineHeight = 28.tsp, color = colors.ink))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (day.moods.isNotEmpty()) {
                Column(Modifier.clickable(role = Role.Button, onClickLabel = "打开心情") { onOpen(day.moods.first()) }) {
                    KindLabel(TimelineEntryKind.Mood)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        day.moods.forEach { MoodChip(it, people) }
                    }
                }
            }
            if (day.photos.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp, top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    day.photos.forEachIndexed { i, p -> TimelinePolaroid(p, vm, i) }
                }
            }
            day.others.forEach { e -> EntryItem(e, people, onClick = { onOpen(e) }) }
        }
    }
}

@Composable
private fun KindLabel(kind: TimelineEntryKind) {
    val look = kind.look()
    val tint = look.tone.color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(look.icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(look.label, style = QichiTheme.typography.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = tint))
    }
}

/** 心情小胶囊：人物标记、心情图标、词、强度。 */
@Composable
private fun MoodChip(e: TimelineEntry, people: People) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val who = e.authorId
    val tint = people.person(who).color()
    val label = e.moodLabel
    Row(
        Modifier.background(tint.copy(alpha = .12f), QichiShapes.pill).padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PersonMark(people.markChar(who), people.person(who), size = 20.dp)
        if (label != null) Icon(label.icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(label?.displayName ?: e.title, style = type.caption.copy(fontSize = 14.tsp, color = colors.ink))
        e.intensity?.let { Text("$it", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted)) }
    }
}

/** 问答、决定、计划、灵感、写作：小标题 + 内容。 */
@Composable
private fun EntryItem(e: TimelineEntry, people: People, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick)) {
        KindLabel(e.kind)
        when (e.kind) {
            TimelineEntryKind.Qna -> {
                Text(e.title, style = type.body.copy(fontWeight = FontWeight.W500, color = colors.ink))
                e.answers.forEach { a ->
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PersonMark(people.markChar(a.authorId), people.person(a.authorId), size = 16.dp, modifier = Modifier.padding(top = 3.dp))
                        Text(a.body, style = type.caption.copy(fontSize = 14.tsp, color = colors.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TimelineEntryKind.Decision -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(e.detail ?: e.title, style = type.body.copy(color = colors.ink))
                    if (e.detail != null) Text(e.title, style = type.caption.copy(fontSize = 12.tsp, color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Seal("定", size = 26.dp, rotation = 8f)
            }
            TimelineEntryKind.Idea -> {
                Text(Tags.strip(e.title).ifBlank { e.title }, style = type.body.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            TimelineEntryKind.Writing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(e.title.ifBlank { "没有标题的" }, style = type.body.copy(color = colors.ink), modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.version?.let { Text("v$it", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted)) }
                e.detail?.let { Text(it, style = type.caption.copy(fontSize = 12.tsp, color = colors.muted)) }
                e.authorId?.let { PersonMark(people.markChar(it), people.person(it), size = 16.dp) }
            }
            else -> {
                Text(e.title, style = type.body.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                e.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** 时间线里的照片：拍立得，下面手写说明，轻轻歪着；第一张贴胶带。 */
@Composable
private fun TimelinePolaroid(e: TimelineEntry, vm: TimelineViewModel, index: Int, width: Dp = 112.dp) {
    val colors = QichiTheme.colors
    Polaroid(
        caption = e.detail,
        rotation = listOf(-3f, 2.5f, -1.5f, 2f)[index % 4],
        tape = if (index == 0) ({ Tape(Modifier.align(Alignment.TopCenter).offset(y = (-8).dp), color = colors.personA, width = 40.dp, rotation = -6f) }) else null,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(vm.urls.thumbnail(e.refId, 400)).crossfade(!QichiTheme.reduceMotion).build(),
            contentDescription = e.detail ?: "两个人都选中的照片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width, width * 0.75f).background(colors.surface),
        )
    }
}

/** 「照片」看法：这个月两人都选中的照片，两列拍立得。 */
@Composable
private fun PhotoWall(photos: List<TimelineEntry>, vm: TimelineViewModel) {
    if (photos.isEmpty()) {
        Text("这个月还没有两个人都选中的照片。右上角可以去选。", style = QichiTheme.typography.caption.copy(color = QichiTheme.colors.muted), modifier = Modifier.padding(top = Spacing.m))
        return
    }
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        photos.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                pair.forEachIndexed { i, p -> TimelinePolaroid(p, vm, row * 2 + i, width = 140.dp) }
            }
        }
    }
}

/** 加载中（按 New-Timeline-Loading）：一枝绿萝和一句手写，下面几块淡色的占位。 */
@Composable
private fun TimelineLoading() {
    val colors = QichiTheme.colors
    val bar = colors.ink.copy(alpha = .08f)
    Column(Modifier.fillMaxWidth().padding(start = Spacing.page, end = Spacing.page, top = Spacing.xs)) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sprig(width = 70.dp)
            HandNote("正在翻找以前的日子……", fontSizeSp = 20f, rotation = -2f)
        }
        repeat(3) { i ->
            Row(
                Modifier.fillMaxWidth().then(if (i == 0) Modifier else Modifier.dashedDivider(colors, atTop = true)).padding(vertical = Spacing.ml).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(24.dp, 10.dp).background(bar, QichiShapes.pill))
                    Box(Modifier.size(34.dp, 26.dp).background(bar, RoundedCornerShape(8.dp)))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(.4f).height(10.dp).background(bar, QichiShapes.pill))
                    Box(Modifier.fillMaxWidth(.92f).height(12.dp).background(bar, QichiShapes.pill))
                    Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(bar, QichiShapes.pill))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(112.dp, 84.dp).background(bar, RoundedCornerShape(4.dp)))
                        Box(Modifier.size(112.dp, 84.dp).background(bar, RoundedCornerShape(4.dp)))
                    }
                }
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
        ItemTopBar("选照片", onBack, feature = Feature.Timeline)
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
