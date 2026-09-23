package app.qichi.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.shared.api.Highlight
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import java.util.UUID
import org.readium.r2.navigator.preferences.Color as ReadiumColor

private enum class ReaderSheet { Toc, Notes, Search }

private val HighlightKind.label: String
    get() = when (this) {
        HighlightKind.Highlight -> "标注"
        HighlightKind.Bookmark -> "书签"
        HighlightKind.Excerpt -> "摘录"
        HighlightKind.Ai -> "AI 解读"
    }

/**
 * 阅读器（按 Reading.dc.html）：返回条（书名、目录、搜索、书签）、Readium 的正文、底部两个人的进度。
 * 选中文字可以「标注」「摘录」；点标注看感想（自己的可以写、可以设为共同可见）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    roomId: UUID,
    bookId: UUID,
    onBack: () -> Unit,
    extraSelectionActions: (ReaderViewModel) -> List<SelectionAction> = { emptyList() },
    vm: ReaderViewModel = hiltViewModel<ReaderViewModel, ReaderViewModel.Factory>(key = "reader-$bookId") { it.create(roomId, bookId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var locator by remember { mutableStateOf<Locator?>(null) }
    var sheet by rememberSaveable { mutableStateOf<ReaderSheet?>(null) }
    var openHighlight by remember { mutableStateOf<UUID?>(null) }

    LaunchedEffect(state.loaded, state.book) { if (state.loaded && state.book == null) onBack() }
    LaunchedEffect(vm) { vm.openHighlight.collect { openHighlight = it } }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // ── 返回条 ──
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(start = 8.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(QichiIcons.Back, "返回", onBack)
            Text(state.book?.title.orEmpty(), style = type.pageTitle.copy(fontSize = 19.tsp, fontWeight = FontWeight.W300, letterSpacing = 0.2.em, color = colors.ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            IconAction(QichiIcons.Outline, "目录", { sheet = ReaderSheet.Toc }, enabled = state.ready)
            IconAction(QichiIcons.Search, "书内搜索", { sheet = ReaderSheet.Search }, enabled = state.ready)
            val marked = vm.bookmarkAt(locator) != null
            IconAction(QichiIcons.Bookmark, if (marked) "去掉书签" else "加书签", { locator?.let(vm::toggleBookmark) },
                enabled = locator != null, tint = if (marked) colors.accent else colors.ink)
        }

        // ── 正文 ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val pub = vm.publication
            when {
                state.ready && pub != null -> {
                    // 书的正文也跟着「大字」放大
                    val scale = type.scale
                    val prefs = remember(colors, scale) {
                        EpubPreferences(
                            backgroundColor = ReadiumColor(colors.background.toArgb()),
                            textColor = ReadiumColor(colors.ink.toArgb()),
                            fontSize = scale.toDouble(),
                        )
                    }
                    val actions = remember(vm) {
                        listOf(
                            SelectionAction(1, "标注") { nav -> scope.launch { nav.currentSelection()?.let { sel ->
                                vm.addHighlight(HighlightKind.Highlight, sel.locator, sel.locator.text.highlight.orEmpty()) { openHighlight = it.id }
                            }; nav.clearSelection() } },
                            SelectionAction(2, "摘录") { nav -> scope.launch { nav.currentSelection()?.let { sel ->
                                vm.addHighlight(HighlightKind.Excerpt, sel.locator, sel.locator.text.highlight.orEmpty()) { openHighlight = it.id }
                            }; nav.clearSelection() } },
                            SelectionAction(3, "解释") { nav -> scope.launch { nav.currentSelection()?.let { sel ->
                                vm.askAi(ReadExplainMode.Explain, sel.locator)?.let { reason -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show() }
                            }; nav.clearSelection() } },
                            SelectionAction(4, "对比") { nav -> scope.launch { nav.currentSelection()?.let { sel ->
                                vm.askAi(ReadExplainMode.Compare, sel.locator)?.let { reason -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show() }
                            }; nav.clearSelection() } },
                        ) + extraSelectionActions(vm)
                    }
                    EpubHost(pub, vm.initialLocator, prefs, actions, onReady = { navigator = it }, modifier = Modifier.fillMaxSize())
                }
                state.error != null -> Column(Modifier.align(Alignment.Center).padding(Spacing.page), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error!!, style = type.body.copy(color = colors.muted))
                    TextAction("再试一次", { vm.retry() })
                }
                else -> Text(
                    state.downloading?.takeIf { it > 0f }?.let { "正在下载 ${(it * 100).toInt()}%" } ?: "正在打开…",
                    style = type.caption.copy(color = colors.faint), modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // ── AI 正在看 / 没得到回答 ──
        if (state.aiPending != null || state.aiFailed) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text("AI", style = type.numeral.copy(fontSize = 17.tsp, color = colors.personB))
                Text(if (state.aiFailed) "没有得到回答" else "正在看这段……", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                if (state.aiFailed) {
                    TextAction("重试", vm::retryAi)
                    TextAction("算了", vm::dismissAi, color = colors.muted)
                }
            }
        }

        // ── 两个人的进度 ──
        ProgressTrack(state, locator)
    }

    // 位置变化：报告给 ViewModel（保存进度）
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        nav.currentLocator.collectLatest { l -> locator = l; vm.onLocator(l) }
    }
    // 标注显示在正文里：我的用强调色，对方共享的用对方的颜色；点一下看感想
    LaunchedEffect(navigator, state.highlights) {
        val nav = navigator ?: return@LaunchedEffect
        val me = state.people.myUserId
        val decorations = state.highlights.map { it.value }.filter { it.kind != HighlightKind.Bookmark }.mapNotNull { h ->
            val l = vm.parseLocator(h.locator) ?: return@mapNotNull null
            val tint = when {
                h.kind == HighlightKind.Ai -> colors.faint
                h.userId == me -> colors.accent
                else -> colors.personB
            }
            Decoration(h.id.toString(), l, Decoration.Style.Highlight(tint.copy(alpha = 0.35f).toArgb(), isActive = h.note != null))
        }
        nav.applyDecorations(decorations, "highlights")
    }
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        nav.addDecorationListener("highlights", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                openHighlight = runCatching { UUID.fromString(event.decoration.id) }.getOrNull()
                return true
            }
        })
    }

    val go: (Locator) -> Unit = { l -> navigator?.go(l, animated = false); sheet = null }
    when (sheet) {
        ReaderSheet.Toc -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            TocSheet(state, onOpen = { link -> navigator?.go(link, animated = false); sheet = null }, onNotes = { sheet = ReaderSheet.Notes })
        }
        ReaderSheet.Notes -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            NotesSheet(state, vm, onGo = go, onOpen = { openHighlight = it; sheet = null })
        }
        ReaderSheet.Search -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            SearchSheet(vm, onGo = go)
        }
        null -> Unit
    }
    openHighlight?.let { id ->
        state.highlights.firstOrNull { it.value.id == id }?.let { h ->
            ModalBottomSheet(onDismissRequest = { openHighlight = null }, containerColor = colors.background) {
                HighlightSheet(h.value, state.people, vm, onDone = { openHighlight = null })
            }
        }
    }
}

/** 底部：一条进度线，上面是两个人各自读到的位置；右边是页码（有的话）。 */
@Composable
private fun ProgressTrack(state: ReaderState, locator: Locator?) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val mine = locator?.locations?.totalProgression ?: state.mine?.progress
    val partnerId = people.partner?.userId
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = Spacing.xl, end = Spacing.page, top = Spacing.xs, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        BoxWithConstraints(Modifier.weight(1f).height(26.dp).semantics(mergeDescendants = true) {
            contentDescription = buildString {
                mine?.let { append("我读到 ${(it * 100).toInt()}%") }
                state.partner?.let { append("，${people.name(it.userId)}读到 ${(it.progress * 100).toInt()}%") }
            }
        }) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(1.dp).background(colors.line2))
            mine?.let { Box(Modifier.align(Alignment.CenterStart).width(maxWidth * it.toFloat()).height(1.dp).background(colors.ink)) }
            state.partner?.let { p ->
                PersonMark(people.markChar(p.userId), people.person(p.userId), size = 18.dp,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 18.dp) * p.progress.toFloat()))
            }
            if (mine != null && people.myUserId != null) {
                PersonMark(people.markChar(people.myUserId), people.person(people.myUserId), size = 18.dp,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 18.dp) * mine.toFloat()))
            }
        }
        locator?.locations?.position?.let { Text(it.toString(), style = type.numeral.copy(fontSize = 19.tsp, color = colors.muted)) }
            ?: mine?.let { Text("${(it * 100).toInt()}%", style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted)) }
    }
    if (partnerId == null) Spacer(Modifier.height(0.dp))
}

@Composable
private fun TocSheet(state: ReaderState, onOpen: (org.readium.r2.shared.publication.Link) -> Unit, onNotes: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("目录", modifier = Modifier.weight(1f))
            TextAction("书签与笔记", onNotes)
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(state.toc) { item ->
                Text(
                    item.link.title ?: item.link.href.toString(),
                    style = type.body.copy(fontSize = (if (item.depth == 0) 16 else 14).tsp, color = if (item.depth == 0) colors.ink else colors.muted),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onOpen(item.link) }
                        .padding(start = (item.depth.coerceAtMost(3) * 16).dp, top = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

@Composable
private fun NotesSheet(state: ReaderState, vm: ReaderViewModel, onGo: (Locator) -> Unit, onOpen: (UUID) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page)) {
        SectionLabel("书签与笔记")
        if (state.highlights.isEmpty()) Text("还没有。选中文字可以标注、摘录；右上角可以加书签。", style = type.caption.copy(color = colors.muted))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(state.highlights, key = { it.value.id }) { local ->
                val h = local.value
                Column(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (h.kind == HighlightKind.Bookmark) vm.parseLocator(h.locator)?.let(onGo) else onOpen(h.id)
                    }.padding(vertical = Spacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        PersonMark(people.markChar(h.userId), people.person(h.userId), size = 16.dp)
                        Text(h.kind.label + (if (h.shared && h.userId == people.myUserId) " · 共同可见" else ""), style = type.caption.copy(color = colors.accent))
                        vm.parseLocator(h.locator)?.locations?.totalProgression?.let { Text("${(it * 100).toInt()}%", style = type.caption.copy(color = colors.faint)) }
                    }
                    if (h.text.isNotBlank()) Text("「${h.text}」", style = type.body.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    h.note?.let { Text(it, style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

@Composable
private fun SearchSheet(vm: ReaderViewModel, onGo: (Locator) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Locator>?>(null) }
    var searching by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Spacing.page)) {
        QuickInput(query, { query = it.take(100) }, placeholder = "在书里找", actionLabel = "找", onSubmit = {
            searching = true
            scope.launch { results = vm.search(query); searching = false }
        })
        when {
            searching -> Text("正在找…", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.s))
            results?.isEmpty() == true -> Text("没有找到。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.s))
        }
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(results.orEmpty()) { l ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onGo(l) }.padding(vertical = Spacing.xs)) {
                    l.title?.let { Text(it, style = type.caption.copy(color = colors.faint), maxLines = 1) }
                    Text("…${l.text.before.orEmpty().takeLast(20)}【${l.text.highlight.orEmpty()}】${l.text.after.orEmpty().take(20)}…",
                        style = type.body.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

/** 一条标注 / 摘录：原文、感想；自己的可以写感想、设为共同可见、删除；对方共享的只能看。 */
@Composable
private fun HighlightSheet(h: Highlight, people: People, vm: ReaderViewModel, onDone: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val mine = h.userId == people.myUserId
    var note by rememberSaveable(h.id) { mutableStateOf(h.note.orEmpty()) }
    var shared by rememberSaveable(h.id) { mutableStateOf(h.shared) }
    var deleting by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PersonMark(people.markChar(h.userId), people.person(h.userId), size = 18.dp)
            Text(if (h.kind == HighlightKind.Ai) "${people.name(h.userId)}请 AI 看的这段 · AI 生成，仅供参考" else "${people.name(h.userId)}的${h.kind.label}",
                style = type.caption.copy(color = colors.muted))
        }
        Text("「${h.text}」", style = type.bodyLarge.copy(color = colors.ink))
        if (h.kind == HighlightKind.Ai) {
            h.note?.let { Text(it, style = type.body.copy(color = colors.ink)) }
            if (mine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("共同可见", style = type.body.copy(color = colors.ink), modifier = Modifier.weight(1f))
                    Switch(shared, { shared = it; vm.updateHighlight(h, h.note, it) }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                }
                TextAction("删除", { deleting = true }, color = colors.muted)
            }
        } else if (mine) {
            QichiTextField(note, { note = it.take(Limits.HIGHLIGHT_NOTE_MAX) }, label = "感想（可以不写）", singleLine = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共同可见", style = type.body.copy(color = colors.ink))
                    Text("打开后${people.partner?.displayName ?: "对方"}也能看到这条和你的感想", style = type.caption.copy(color = colors.muted))
                }
                Switch(shared, { shared = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextAction("保存", { vm.updateHighlight(h, note, shared); onDone() })
                TextAction("删除", { deleting = true }, color = colors.muted)
            }
        } else {
            h.note?.let { Text(it, style = type.body.copy(color = colors.ink)) } ?: Text("没有写感想。", style = type.caption.copy(color = colors.muted))
        }
        Spacer(Modifier.height(Spacing.s))
    }
    if (deleting) {
        ConfirmDialog("删掉这条${h.kind.label}？", "删掉后不能恢复。", "删掉", onConfirm = { deleting = false; vm.deleteHighlight(h); onDone() }, onDismiss = { deleting = false })
    }
}
