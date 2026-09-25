package app.qichi.feature.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TagChip
import app.qichi.core.designsystem.component.TagFilterRow
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.DiffView
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.ArchiveRevision
import app.qichi.shared.api.Message
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.Tags
import app.qichi.shared.util.Diff
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val hm = DateTimeFormatter.ofPattern("HH:mm")

private fun whenText(at: Instant, zone: ZoneId, today: LocalDate): String {
    val t = at.atZone(zone)
    return "${relativeDay(t.toLocalDate(), today).first} ${t.format(hm)}"
}

private fun excerpt(text: String, max: Int = 40): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}

// ───────────────────────── 列表 ─────────────────────────

/**
 * 档案：长期共同事实，按种类筛选（全部时按种类分组）。
 * [fromMessageId] 不为空时是从聊天「存进档案」进来的：打开新条目，用那条消息预填。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveListScreen(
    roomId: UUID,
    fromMessageId: UUID?,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    onOpenTags: () -> Unit = {},
    vm: ArchiveListViewModel = hiltViewModel<ArchiveListViewModel, ArchiveListViewModel.Factory>(key = "archive-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var creating by rememberSaveable { mutableStateOf(fromMessageId != null) }
    var source by remember { mutableStateOf<Message?>(null) }
    LaunchedEffect(fromMessageId) { fromMessageId?.let { source = vm.message(it) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            var tag by rememberSaveable { mutableStateOf<String?>(null) }
            FeatureTopBar(
                Feature.Archive, onBack,
                actions = listOf(BarAction("标签", QichiIcons.Tag, onOpenTags)),
                menu = listOf(MenuAction("所有种类", { vm.filter(null) })) + ArchiveKind.entries.map { k -> MenuAction("只看「${k.label}」", { vm.filter(k) }) },
            )
            val tags = remember(state.shown) { app.qichi.feature.ideas.topTags(state.shown.map { it.value.title + "\n" + it.value.body }) }
            if (tags.isNotEmpty()) TagFilterRow(tags, tag, { tag = it })
            state.filter?.let { k ->
                Row(Modifier.padding(horizontal = Spacing.page), verticalAlignment = Alignment.CenterVertically) {
                    Text("只看「${k.label}」", style = type.caption.copy(color = colors.muted))
                    TextAction("看全部", { vm.filter(null) }, color = colors.accent)
                }
            }
            val shown = state.shown.filter { tag == null || Tags.has(it.value.title + "\n" + it.value.body, tag!!) }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.loaded && shown.isEmpty()) {
                    Text(
                        when {
                            tag != null -> "#$tag 下面还没有档案。"
                            state.filter == null -> "还没有档案。两个人慢慢确认下来的事——喜欢什么、说好了什么、在意什么——可以记在这里。"
                            else -> "还没有「${state.filter!!.label}」。"
                        },
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m),
                    )
                }
                shown.forEachIndexed { i, item -> FactCard(item, state.people, state.zone, i) { onOpen(item.value.id) } }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("新条目", { source = null; creating = true })
    }

    if (creating) {
        ItemEditor(
            heading = "新条目",
            initialKind = state.filter ?: ArchiveKind.Preference,
            chooseKind = true,
            // 短消息直接当作那一句话；长消息取开头做标题，全文放进补充
            initialTitle = source?.body?.trim()?.let { if (it.length <= Limits.ARCHIVE_TITLE_LENGTH.last && '\n' !in it) it else excerpt(it, 30) }.orEmpty(),
            initialBody = source?.body?.trim()?.takeIf { it.length > Limits.ARCHIVE_TITLE_LENGTH.last || '\n' in it }.orEmpty(),
            source = source,
            people = state.people,
            onDismiss = { creating = false },
            onSave = { kind, title, body, keepSource ->
                creating = false
                vm.create(kind, title, body, if (keepSource) source?.id else null, onCreated = onOpen)
            },
        )
    }
}

/** 每种档案的图标和颜色（卡片顶上的色条、小字）。 */
internal val ArchiveKind.icon: ImageVector
    get() = when (this) {
        ArchiveKind.Preference -> QichiIcons.Heart
        ArchiveKind.Consensus -> QichiIcons.Rings
        ArchiveKind.Boundary -> QichiIcons.Lock
        ArchiveKind.Concern -> QichiIcons.Drop
        ArchiveKind.Milestone -> QichiIcons.Flag
        ArchiveKind.Decision -> QichiIcons.Sign
    }

internal val ArchiveKind.tone: FeatureTone
    get() = when (this) {
        ArchiveKind.Preference -> FeatureTone.PersonA
        ArchiveKind.Consensus, ArchiveKind.Milestone -> FeatureTone.PersonB
        ArchiveKind.Boundary, ArchiveKind.Decision -> FeatureTone.Accent
        ArchiveKind.Concern -> FeatureTone.Muted
    }

private val MD = DateTimeFormatter.ofPattern("MM.dd")
private val YMD = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/**
 * 一条档案是一张卡片（按 New-Archive）：顶上一道种类色条，小字种类 + 谁；那句话；
 * 下面是 #标签、「来自聊天」、日期和第几版。卡片轻轻左右倾斜。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactCard(local: Local<ArchiveItem>, people: People, zone: ZoneId, index: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val item = local.value
    val tint = item.kind.tone.color
    val rotation = listOf(-.4f, .4f, -.3f, .3f, 0f)[index % 5]
    val tags = Tags.parse(item.title + "\n" + item.body)
    Column(
        Modifier.rotate(rotation).fillMaxWidth().lift(colors, QichiShapes.paper).clip(QichiShapes.paper).background(colors.card)
            .drawBehind { drawRect(tint.copy(alpha = .7f), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
            .clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(item.kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Text(item.kind.label, style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = tint), modifier = Modifier.weight(1f))
            PersonMark(people.markChar(item.revisedBy), people.person(item.revisedBy), size = 18.dp)
        }
        Text(Tags.strip(item.title).ifBlank { item.title }, style = type.bodyLarge.copy(lineHeight = 26.4.tsp, color = colors.ink), modifier = Modifier.padding(top = 4.dp))
        if (item.body.isNotBlank()) {
            Text(Tags.strip(item.body), style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            tags.forEach { TagChip(it) }
            if (item.sourceMessageId != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(QichiIcons.Arrow, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
                    Text("来自聊天", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
                }
            }
            val date = item.createdAt.atZone(zone).toLocalDate()
            Text(date.format(if (date.year == java.time.LocalDate.now(zone).year) MD else YMD), style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
            if (item.currentRevision > 1) Text("v${item.currentRevision}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
            if (local.isPending) Text("待发送", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
        }
    }
}

// ───────────────────────── 详情 ─────────────────────────

/** 一条档案：当前内容、来源消息（点了跳回聊天）、历次修订（逐次对比）。 */
@Composable
fun ArchiveDetailScreen(
    roomId: UUID,
    itemId: UUID,
    onBack: () -> Unit,
    onOpenMessage: (UUID) -> Unit,
    vm: ArchiveDetailViewModel = hiltViewModel<ArchiveDetailViewModel, ArchiveDetailViewModel.Factory>(key = "archive-$itemId") {
        it.create(roomId, itemId)
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val revisions by vm.revisions.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var openRevision by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.loaded, state.item) { if (state.loaded && state.item == null) onBack() }
    // 修订号变了（自己或对方修订了）就重取历史
    LaunchedEffect(state.item?.value?.currentRevision) { if (state.item != null) vm.loadRevisions() }

    val local = state.item
    val item = local?.value
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ItemTopBar(item?.kind?.label.orEmpty(), onBack, feature = Feature.Archive,
            actions = listOf(BarAction("修订", QichiIcons.Pen, { editing = true }, enabled = item != null && !state.conflict)),
            menu = listOf(MenuAction("删除", { deleting = true }, danger = true)))
        if (item == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(item.title, style = type.pageTitle.copy(fontSize = 24.tsp, lineHeight = 34.tsp, color = colors.ink),
                modifier = Modifier.semantics { heading() })
            if (item.body.isNotEmpty()) Text(item.body, style = type.body.copy(fontSize = 17.tsp, lineHeight = 30.tsp, color = colors.ink),
                modifier = Modifier.padding(top = Spacing.s))
            Row(Modifier.padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PersonMark(state.people.markChar(item.revisedBy), state.people.person(item.revisedBy), size = 18.dp)
                Text("${state.people.name(item.revisedBy)} · ${whenText(item.updatedAt, state.zone, state.today)}", style = type.caption.copy(color = colors.muted))
            }
            when {
                state.conflict -> Column(Modifier.padding(top = Spacing.s)) {
                    Text("对方先修订了这一条，你这次的修订没有保存。", style = type.caption.copy(color = colors.accent))
                    TextAction("放弃我的修订，看最新的", vm::abandon, color = colors.muted)
                }
                local.isFailed -> Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("发送失败", style = type.caption.copy(color = colors.accent))
                    TextAction("重试", vm::retry)
                    TextAction("放弃", vm::abandon, color = colors.muted)
                }
            }

            item.sourceMessageId?.let { msgId ->
                SectionLabel("来源", modifier = Modifier.padding(top = Spacing.l))
                val m = state.source
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClickLabel = "在聊天里看") { onOpenMessage(msgId) },
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(colors.line2))
                    Text(
                        if (m != null) "${state.people.name(m.authorId)}：${excerpt(m.body, 60)}  · ${whenText(m.createdAt, state.zone, state.today)}"
                        else "一条聊天消息（点开在聊天里看）",
                        style = type.caption.copy(color = colors.muted),
                        modifier = Modifier.padding(start = Spacing.s, top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            SectionLabel("修订历史", modifier = Modifier.padding(top = Spacing.l))
            val list = revisions
            if (list == null) {
                Text("联网后能看到历次修订。", style = type.caption.copy(color = colors.muted))
            } else {
                list.forEach { r ->
                    val previous = list.firstOrNull { it.revision == r.revision - 1 }
                    RevisionRow(r, previous, state.people, state.zone, state.today, open = openRevision == r.revision,
                        onToggle = { openRevision = if (openRevision == r.revision) null else r.revision })
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (editing && item != null) {
        ItemEditor(
            heading = "修订",
            initialKind = item.kind,
            chooseKind = false,
            initialTitle = item.title,
            initialBody = item.body,
            source = state.source,
            people = state.people,
            onDismiss = { editing = false },
            onSave = { _, title, body, keepSource ->
                editing = false
                vm.revise(title, body, if (keepSource) item.sourceMessageId else null)
            },
        )
    }
    if (deleting) {
        ConfirmDialog("删除这条档案？", "会进回收站，可以恢复。", "删除", onConfirm = { deleting = false; vm.delete() }, onDismiss = { deleting = false })
    }
}

private fun ArchiveRevision.asText(): String = if (body.isEmpty()) title else "$title\n\n$body"

@Composable
private fun RevisionRow(r: ArchiveRevision, previous: ArchiveRevision?, people: People, zone: ZoneId, today: LocalDate, open: Boolean, onToggle: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "收起" else "看这次改了什么", onClick = onToggle).padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(if (r.revision == 1) "记下" else "第 ${r.revision - 1} 次修订", style = type.body.copy(color = colors.ink))
            PersonMark(people.markChar(r.authorId), people.person(r.authorId), size = 18.dp)
            Text(whenText(r.createdAt, zone, today), style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "展开", style = type.caption.copy(color = colors.faint))
        }
        if (open) {
            DiffView(Diff.lines(previous?.asText().orEmpty(), r.asText()), modifier = Modifier.padding(bottom = Spacing.s))
        }
    }
}

// ───────────────────────── 编辑 ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(
    heading: String,
    initialKind: ArchiveKind,
    chooseKind: Boolean,
    initialTitle: String,
    initialBody: String,
    source: Message?,
    people: People,
    onDismiss: () -> Unit,
    onSave: (kind: ArchiveKind, title: String, body: String, keepSource: Boolean) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var kind by rememberSaveable { mutableStateOf(initialKind) }
        var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
        var body by rememberSaveable(initialBody) { mutableStateOf(initialBody) }
        var keepSource by rememberSaveable { mutableStateOf(true) }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(heading, style = type.pageTitle.copy(color = colors.ink))
            if (chooseKind) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveKind.entries.forEach { k -> ChoicePill(k.label, kind == k, { kind = k }) }
                }
            }
            QichiTextField(title, { title = it.take(Limits.ARCHIVE_TITLE_LENGTH.last) }, label = "一句话", placeholder = "比如：约饭先挑安静的地方",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            QichiTextField(body, { body = it.take(Limits.ARCHIVE_BODY_MAX) }, label = "补充（可以不写）", singleLine = false)
            if (source != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(
                        if (keepSource) "来源：${people.name(source.authorId)}「${excerpt(source.body, 24)}」" else "不关联来源消息",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f),
                    )
                    TextAction(if (keepSource) "不关联" else "关联", { keepSource = !keepSource }, color = colors.muted)
                }
            }
            PrimaryButton("保存", { onSave(kind, title, body, keepSource) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
    }
}
