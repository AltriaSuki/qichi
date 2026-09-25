package app.qichi.feature.board

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Illustration
import app.qichi.core.designsystem.component.decor.Postmark
import app.qichi.core.designsystem.component.decor.Scene
import app.qichi.core.designsystem.component.decor.Stamp
import app.qichi.core.designsystem.component.decor.WaxSeal
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.MarkdownView
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardPostRevision
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.rules.BoardRules
import app.qichi.shared.rules.Limits
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch

private val hm = DateTimeFormatter.ofPattern("HH:mm")

private val BoardReactionKind.label: String
    get() = when (this) {
        BoardReactionKind.Like -> "喜欢"
        BoardReactionKind.Hug -> "拥抱"
        BoardReactionKind.Support -> "支持"
    }

private fun whenText(at: Instant, zone: ZoneId, today: LocalDate): String {
    val t = at.atZone(zone)
    return "${relativeDay(t.toLocalDate(), today).first} ${t.format(hm)}"
}

// ───────────────────────── 主题列表 ─────────────────────────

/** 留言板：主题列表（置顶在前）、本机搜索、新主题（标题 + 第一条留言）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (topicId: UUID, postId: UUID?) -> Unit,
    vm: BoardListViewModel = hiltViewModel<BoardListViewModel, BoardListViewModel.Factory>(key = "board-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var creating by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = searchOpen) { searchOpen = false; vm.search("") }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Board, onBack, actions = listOf(BarAction("搜索", QichiIcons.Search, { searchOpen = !searchOpen; if (!searchOpen) vm.search("") })))
            if (searchOpen) {
                QuickInput(state.query, vm::search, placeholder = "搜索留言和标题", actionLabel = "清空", onSubmit = { vm.search("") },
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                if (state.searching) {
                    if (state.topicHits.isEmpty() && state.postHits.isEmpty()) {
                        Text("没有找到。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                    }
                    state.topicHits.forEachIndexed { i, it -> EnvelopeCard(it, state.people, state.zone, i) { onOpen(it.topic.value.id, null) } }
                    if (state.postHits.isNotEmpty()) SectionLabel("留言", modifier = Modifier.padding(top = Spacing.m))
                    state.postHits.forEach { hit ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(hit.post.topicId, hit.post.id) }.padding(vertical = Spacing.s)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                PersonMark(state.people.markChar(hit.post.authorId), state.people.person(hit.post.authorId), size = 18.dp)
                                Text("${hit.topicTitle} · ${whenText(hit.post.createdAt, state.zone, state.today)}", style = type.caption.copy(color = colors.muted),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(BoardRules.quoteExcerpt(hit.post.body), style = type.body.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = Spacing.xxs))
                        }
                    }
                } else {
                    if (state.loaded && state.topics.isEmpty()) {
                        Text("还没有留言。想慢慢说清楚的话，可以写在这里。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                        TextAction("写第一个主题", { creating = true })
                    }
                    state.topics.forEachIndexed { i, it -> EnvelopeCard(it, state.people, state.zone, i) { onOpen(it.topic.value.id, null) } }
                }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("写留言", { creating = true })
    }

    if (creating) {
        ModalBottomSheet(onDismissRequest = { creating = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background) {
            var title by rememberSaveable { mutableStateOf("") }
            var body by rememberSaveable { mutableStateOf("") }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(horizontal = Spacing.page, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                QichiTextField(title, { title = it.take(Limits.BOARD_TITLE_LENGTH.last) }, label = "主题", placeholder = "比如：关于搬家",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                QichiTextField(body, { body = it.take(Limits.BOARD_POST_LENGTH.last) }, label = "第一条留言", singleLine = false)
                PrimaryButton("发出", { creating = false; vm.create(title, body) { id -> onOpen(id, null) } },
                    enabled = title.isNotBlank() && body.isNotBlank(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")

/**
 * 一个留言主题是一只信封（按 New-Messages）：一道封口线，右上角一张邮票盖着邮戳，
 * 标题、最近一条留言的开头、几条回复，右下角手写署名；置顶的左上角一枚蜡封。卡片轻轻歪着。
 */
@Composable
private fun EnvelopeCard(summary: TopicSummary, people: People, zone: ZoneId, index: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val topic = summary.topic.value
    val author = topic.authorId
    val tint = people.person(author).color()
    val rotation = listOf(-.6f, .7f, -.5f, .4f)[index % 4]
    val flap = colors.line2
    Box(Modifier.rotate(rotation)) {
        Column(
            Modifier.fillMaxWidth().lift(colors).clip(QichiShapes.card).background(colors.card)
                .drawBehind {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f); lineTo(size.width / 2, 26.dp.toPx()); lineTo(size.width, 0f)
                    }
                    drawPath(p, flap, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                .clickable(role = Role.Button, onClickLabel = "打开主题", onClick = onClick)
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Column(Modifier.padding(end = 84.dp, top = 12.dp)) {
                Text(topic.title, style = type.headline.copy(lineHeight = 24.6.tsp, color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                summary.lastPost?.let { last ->
                    Text(BoardRules.quoteExcerpt(last.body), style = type.preview.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val replies = (summary.postCount - 1).coerceAtLeast(0)
                if (replies > 0) {
                    Text("$replies", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                    Text("回复", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
                } else {
                    Text("还没有回复", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
                }
                Spacer(Modifier.weight(1f))
                HandNote("—— ${people.name(author)}", fontSizeSp = 18f, color = tint, rotation = -3f)
            }
        }
        // 右上角：邮票 + 邮戳
        Stamp(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp), width = 50.dp, height = 58.dp, rotation = 4f) {
            Illustration(boardScene(topic.id), Modifier.fillMaxSize())
        }
        Postmark("栖迟", summary.lastAt.atZone(zone).format(MD), Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 34.dp), size = 40.dp, rotation = -18f, color = tint, waves = false)
        if (topic.pinnedAt != null) {
            WaxSeal(people.markChar(author), Modifier.offset(x = (-10).dp, y = (-12).dp), size = 40.dp, color = tint)
        }
    }
}

private fun boardScene(id: UUID): Scene = Scene.entries[Math.floorMod(id.hashCode() * 31, Scene.entries.size)]

// ───────────────────────── 主题 ─────────────────────────

/**
 * 一个主题下的留言（旧的在前）：引用、修订（已修订的可看历史）、喜欢 / 拥抱 / 支持；
 * 底部写留言（没写完的存在本机）。断网时照样能写，联网后补发。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicScreen(
    roomId: UUID,
    topicId: UUID,
    focusPostId: UUID?,
    onBack: () -> Unit,
    vm: TopicViewModel = hiltViewModel<TopicViewModel, TopicViewModel.Factory>(key = "topic-$topicId") { it.create(roomId, topicId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deletingTopic by remember { mutableStateOf(false) }
    var revising by remember { mutableStateOf<BoardPost?>(null) }
    var deleting by remember { mutableStateOf<BoardPost?>(null) }
    var history by remember { mutableStateOf<BoardPost?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(topicId) { vm.loadDraft()?.let { if (draft.isEmpty()) draft = it } }

    LaunchedEffect(state.loaded, state.topic) { if (state.loaded && state.topic == null) onBack() }
    // 从搜索点进来：滚到那一条
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.posts.size) {
        if (!focused && focusPostId != null) {
            val index = state.posts.indexOfFirst { it.post.value.id == focusPostId }
            if (index >= 0) { list.scrollToItem(index); focused = true }
        }
    }
    fun jumpTo(postId: UUID) {
        val index = state.posts.indexOfFirst { it.post.value.id == postId }
        if (index >= 0) scope.launch { list.animateScrollToItem(index) }
    }

    val topic = state.topic?.value
    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        ItemTopBar(topic?.title.orEmpty(), onBack, feature = Feature.Board, menu = listOfNotNull(
            topic?.let { t -> MenuAction(if (t.pinnedAt != null) "取消置顶" else "置顶", { vm.setPinned(t.pinnedAt == null) }) },
            MenuAction("改标题", { renaming = true }),
            MenuAction("删除主题", { deletingTopic = true }, danger = true),
        ))
        LazyColumn(Modifier.weight(1f), state = list) {
            items(state.posts, key = { it.post.value.id }) { item ->
                PostCard(
                    item = item, people = state.people, zone = state.zone, today = state.today,
                    onQuote = { vm.quote(item.post.value) },
                    onJumpToQuote = { item.post.value.quotePostId?.let(::jumpTo) },
                    onReact = { vm.toggleReaction(item, it) },
                    onRevise = { revising = item.post.value },
                    onDelete = { deleting = item.post.value },
                    onHistory = { history = item.post.value },
                    onRetry = { vm.retry(item.post.value) },
                    onAbandon = { vm.abandon(item.post.value) },
                )
            }
            item { Spacer(Modifier.height(Spacing.l)) }
        }
        // ── 写留言 ──
        Column(Modifier.navigationBarsPadding().padding(start = Spacing.m, end = Spacing.m, top = Spacing.xs, bottom = Spacing.s)) {
            state.quote?.let { q ->
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.s, bottom = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("引用 ${state.people.name(q.authorId)}：${BoardRules.quoteExcerpt(q.body)}", style = type.caption.copy(color = colors.muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconAction(QichiIcons.Close, "不引用了", { vm.quote(null) }, iconSize = 16, tint = colors.muted)
                }
            }
            QuickInput(
                draft,
                { draft = it.take(Limits.BOARD_POST_LENGTH.last); vm.onDraft(draft) },
                placeholder = "写一条留言",
                actionLabel = "发出",
                onSubmit = { vm.send(draft) { draft = "" } },
                multiline = true,
            )
        }
    }

    revising?.let { post ->
        var text by rememberSaveable(post.id) { mutableStateOf(post.body) }
        AlertDialog(
            onDismissRequest = { revising = null },
            containerColor = colors.paper,
            title = { Text("修订", style = type.pageTitle.copy(color = colors.ink)) },
            text = { QichiTextField(text, { text = it.take(Limits.BOARD_POST_LENGTH.last) }, label = "留言", singleLine = false) },
            confirmButton = { TextAction("保存", { vm.revise(post, text); revising = null }, enabled = text.isNotBlank() && text.trim() != post.body) },
            dismissButton = { TextAction("取消", { revising = null }, color = colors.muted) },
        )
    }
    deleting?.let { post ->
        ConfirmDialog("删除这条留言？", "会进回收站，可以恢复。", "删除", onConfirm = { vm.deletePost(post); deleting = null }, onDismiss = { deleting = null })
    }
    history?.let { post -> RevisionsDialog(post, state.zone, state.today, load = { vm.revisions(post) }, onDismiss = { history = null }) }
    if (renaming && topic != null) {
        var title by rememberSaveable { mutableStateOf(topic.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = colors.paper,
            title = { Text("改标题", style = type.pageTitle.copy(color = colors.ink)) },
            text = { QichiTextField(title, { title = it.take(Limits.BOARD_TITLE_LENGTH.last) }, label = "主题") },
            confirmButton = { TextAction("保存", { vm.rename(title); renaming = false }, enabled = title.isNotBlank()) },
            dismissButton = { TextAction("取消", { renaming = false }, color = colors.muted) },
        )
    }
    if (deletingTopic) {
        ConfirmDialog("删除这个主题？", "主题和下面的留言一起进回收站，可以恢复。", "删除",
            onConfirm = { deletingTopic = false; vm.deleteTopic() }, onDismiss = { deletingTopic = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostCard(
    item: PostItem,
    people: People,
    zone: ZoneId,
    today: LocalDate,
    onQuote: () -> Unit,
    onJumpToQuote: () -> Unit,
    onReact: (BoardReactionKind) -> Unit,
    onRevise: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val post = item.post.value
    val mine = post.authorId == people.myUserId
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PersonMark(people.markChar(post.authorId), people.person(post.authorId), size = 20.dp)
            Text(people.name(post.authorId), style = type.caption.copy(color = colors.muted))
            Text(whenText(post.createdAt, zone, today), style = type.caption.copy(color = colors.faint))
            Spacer(Modifier.weight(1f))
            if (post.revisedAt != null) {
                Text("已修订", style = type.caption.copy(color = colors.accent),
                    modifier = Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClickLabel = "看修订历史", onClick = onHistory).padding(top = 6.dp))
            }
            if (item.post.isPending) Text("待发送", style = type.caption.copy(color = colors.faint))
        }
        if (post.quoteExcerpt != null) {
            Row(
                Modifier.padding(top = Spacing.xs).height(IntrinsicSize.Min)
                    .clickable(enabled = post.quotePostId != null, role = Role.Button, onClickLabel = "跳到被引用的留言", onClick = onJumpToQuote),
            ) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.line2))
                Text("${people.name(post.quoteAuthorId)}：${post.quoteExcerpt}", style = type.caption.copy(color = colors.muted),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.s))
            }
        }
        MarkdownView(post.body, 16.tsp, 1.8f, modifier = Modifier.padding(top = Spacing.xs))
        when {
            item.conflict -> Column {
                Text("另一台手机上改过这条留言，你这次的修订没有保存。", style = type.caption.copy(color = colors.accent))
                TextAction("放弃我的修订", onAbandon, color = colors.muted)
            }
            item.post.isFailed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("发送失败", style = type.caption.copy(color = colors.accent))
                TextAction("重试", onRetry)
                TextAction("放弃", onAbandon, color = colors.muted)
            }
        }
        // ── 回应 ──
        FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardReactionKind.entries.forEach { kind ->
                val count = item.reactions[kind].orEmpty().size
                if (mine) {
                    // 自己的留言：只看得到收到的回应
                    if (count > 0) Text("${kind.label} ${count}", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(end = 6.dp))
                } else {
                    Pill(if (count > 0) "${kind.label} $count" else kind.label, onClick = { onReact(kind) }, selected = kind in item.mine)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextAction("引用", onQuote, color = colors.muted)
            if (mine) TextAction("修订", onRevise, color = colors.muted, enabled = !item.post.isPending || post.seq > 0)
            TextAction("删除", onDelete, color = colors.muted)
        }
    }
}

@Composable
private fun RevisionsDialog(post: BoardPost, zone: ZoneId, today: LocalDate, load: suspend () -> List<BoardPostRevision>?, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var revisions by remember { mutableStateOf<List<BoardPostRevision>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(post.id) { load().let { revisions = it; failed = it == null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
        title = { Text("修订历史", style = type.pageTitle.copy(color = colors.ink)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text("现在是第 ${post.revision} 版" + (post.revisedAt?.let { "，${whenText(it, zone, today)} 修订" } ?: ""),
                    style = type.caption.copy(color = colors.muted))
                val list = revisions
                when {
                    list != null -> list.forEach { r ->
                        Column {
                            Text("第 ${r.revision} 版 · ${whenText(r.createdAt, zone, today)}", style = type.caption.copy(color = colors.muted))
                            Text(r.body, style = type.body.copy(color = colors.ink))
                        }
                    }
                    failed -> Text("需要联网才能看修订历史。", style = type.body.copy(color = colors.muted))
                    else -> Text("正在取…", style = type.caption.copy(color = colors.faint))
                }
            }
        },
        confirmButton = { TextAction("关闭", onDismiss, color = colors.muted) },
    )
}
