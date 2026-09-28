package app.qichi.feature.reading

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.data.ReadingSettings
import app.qichi.core.reading.PageKeys
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.SwitchRow
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.Ribbon
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.MarkdownView
import app.qichi.shared.api.Highlight
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.rules.Limits
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator

private enum class ReaderSheet { Toc, Notes, Search, Settings }

private val HighlightKind.label: String
    get() = when (this) {
        HighlightKind.Highlight -> "标注"
        HighlightKind.Bookmark -> "书签"
        HighlightKind.Excerpt -> "摘录"
        HighlightKind.Ai -> "AI 解读"
    }

/**
 * 阅读器：书页铺满整屏（P12-01 沉浸阅读），点中间叫出返回条（书名、目录、搜索、书签）和底部两个人的进度，点左右边缘翻页。
 * 选中文字可以「标注」「摘录」、请 AI「解释」「对比」，或者「问 AI…」按自己的要求问（常用提示词，P14-05）；
 * 点标注看感想（自己的可以写、可以设为共同可见）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // 「问 AI…」：选中的那段（面板开着时不为空）；常用提示词的管理面板
    var askingAbout by remember { mutableStateOf<Locator?>(null) }
    var managingPrompts by rememberSaveable { mutableStateOf(false) }
    val prompts by vm.prompts.collectAsStateWithLifecycle()

    LaunchedEffect(state.loaded, state.book) { if (state.loaded && state.book == null) onBack() }
    LaunchedEffect(vm) { vm.openHighlight.collect { openHighlight = it } }
    LaunchedEffect(vm) { vm.message.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    // ── 沉浸阅读（P12-01）：书页铺满整屏；顶栏、进度、状态栏默认收起，点页面中间叫出 / 收起 ──
    var chromeWanted by rememberSaveable { mutableStateOf(false) }
    // 还没打开、出错了、开着读屏时一直显示，免得找不到返回
    val talkBack = remember { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    val chrome = chromeWanted || !state.ready || state.error != null || talkBack
    val reduceMotion = QichiTheme.reduceMotion
    val marked = vm.bookmarkAt(locator) != null
    SystemBarsVisible(chrome)

    // 字号、行距、页边距、翻页方式（P20-01）；书的正文也跟着「大字」放大
    val readingSettings by vm.settings.collectAsStateWithLifecycle()
    val paper = colors.paper.toArgb()
    val ink = colors.ink.toArgb()
    val prefs = remember(readingSettings, paper, ink, type.scale) { epubPreferences(readingSettings ?: ReadingSettings(), paper, ink, type.scale) }
    // 阅读页开着时改了设置、天色随时间变了：直接换到正在读的书上（以前要退出再进才生效）
    LaunchedEffect(navigator, prefs) { navigator?.submitPreferences(prefs) }
    // 音量键翻页：按下时翻，抬起也吃掉（不调音量）
    val volumeKeys = readingSettings?.volumeKeys == true
    DisposableEffect(navigator, volumeKeys, reduceMotion) {
        val nav = navigator
        if (nav != null && volumeKeys) {
            PageKeys.handler = { e ->
                val forward = when (e.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> false
                    else -> null
                }
                if (forward != null && e.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (forward) nav.goForward(animated = !reduceMotion) else nav.goBackward(animated = !reduceMotion)
                }
                forward != null
            }
        }
        onDispose { PageKeys.handler = null }
    }
    // 读书时屏幕不自己熄灭；10 分钟没翻页就恢复系统的熄屏时间，放下手机不会一直亮着
    val view = LocalView.current
    LaunchedEffect(locator) {
        view.keepScreenOn = true
        delay(KEEP_SCREEN_ON_MS)
        view.keepScreenOn = false
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }

    Box(Modifier.fillMaxSize().background(colors.paper)) {
        // 正文：上面让出状态栏（收起时也按它的高度留，叫出时文字不跳），下面留一行页码
        val topInset = with(LocalDensity.current) { WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).getTop(this).toDp() }
        val bottomInset = with(LocalDensity.current) { WindowInsets.systemBarsIgnoringVisibility.getBottom(this).toDp() }
        Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset + PAGE_NUMBER_HEIGHT)) {
            val pub = vm.publication
            when {
                // 本机的阅读设置读到之前先不排版，免得先按默认字号排一遍再跳
                state.ready && pub != null && readingSettings != null -> {
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
                            // 按自己的要求问（P14-05）：先打开面板选常用的或写一句
                            SelectionAction(5, "问 AI…") { nav -> scope.launch { nav.currentSelection()?.let { sel ->
                                when (val reason = vm.aiBlockedReason()) {
                                    null -> askingAbout = sel.locator
                                    else -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
                                }
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
        // 书签带：只在这一页加了书签时贴在右上角，不占正文的位置
        if (marked) Ribbon(Modifier.align(Alignment.TopEnd).padding(end = 28.dp), height = topInset + 34.dp)

        // 收起时底部只留一行小页码
        if (!chrome) PageNumber(state, locator, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).height(PAGE_NUMBER_HEIGHT))

        val enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(160))
        val exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(160))
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = enter, exit = exit) {
            ItemTopBar(
                state.book?.title.orEmpty(), onBack, feature = Feature.Reading,
                modifier = Modifier.background(colors.background).dashedDivider(colors),
                actions = listOf(
                    BarAction("目录", QichiIcons.Toc, { sheet = ReaderSheet.Toc }, enabled = state.ready),
                    BarAction(if (marked) "去掉书签" else "加书签", QichiIcons.Bookmark, { locator?.let(vm::toggleBookmark) },
                        enabled = locator != null, tint = if (marked) colors.accent else null),
                    BarAction("字号与排版", QichiIcons.TextSize, { sheet = ReaderSheet.Settings }, enabled = readingSettings != null),
                ),
                menu = listOf(MenuAction("书内搜索", { sheet = ReaderSheet.Search }, enabled = state.ready)),
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // ── AI 正在看 / 没得到回答（收起时也显示）──
            if (state.aiPending != null || state.aiFailed) {
                Row(
                    Modifier.fillMaxWidth().background(colors.background).padding(horizontal = Spacing.page, vertical = Spacing.xxs)
                        .then(if (chrome) Modifier else Modifier.padding(bottom = bottomInset + PAGE_NUMBER_HEIGHT)),
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
            // ── 两个人的进度（叫出时）──
            AnimatedVisibility(chrome, enter = enter, exit = exit) {
                ProgressTrack(state, locator, Modifier.background(colors.background).dashedDivider(colors, atTop = true))
            }
        }
    }

    // 点左右边缘翻页，点中间叫出 / 收起顶栏和进度
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        nav.addInputListener(DirectionalNavigationAdapter(nav, animatedTransition = !reduceMotion))
        nav.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                chromeWanted = !chromeWanted
                return true
            }
        })
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
            // 我的：暮玫瑰淡底；对方共享的：对方颜色的波浪下划线；AI 的：淡灰底
            val style: Decoration.Style = when {
                h.kind == HighlightKind.Ai -> Decoration.Style.Highlight(colors.faint.copy(alpha = 0.3f).toArgb(), isActive = h.note != null)
                h.userId == me -> Decoration.Style.Highlight(colors.accent.copy(alpha = 0.18f).toArgb(), isActive = h.note != null)
                else -> WavyUnderline((if (state.people.person(h.userId) == Person.A) colors.personA else colors.personB).toArgb())
            }
            Decoration(h.id.toString(), l, style)
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
            TocSheet(state, locator, onOpen = { link -> navigator?.go(link, animated = false); sheet = null }, onNotes = { sheet = ReaderSheet.Notes })
        }
        ReaderSheet.Notes -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            NotesSheet(state, vm, onGo = go, onOpen = { openHighlight = it; sheet = null })
        }
        ReaderSheet.Search -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            SearchSheet(vm, onGo = go)
        }
        ReaderSheet.Settings -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = colors.background) {
            readingSettings?.let { ReadingSettingsSheet(it, vm::setSettings) }
        }
        null -> Unit
    }
    askingAbout?.let { l ->
        AskAiSheet(
            selected = l.text.highlight.orEmpty(),
            prompts = prompts,
            onAsk = { wish ->
                askingAbout = null
                vm.askAi(ReadExplainMode.Custom, l, wish)?.let { reason -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show() }
            },
            onSavePrompt = { p -> vm.savePrompts(prompts + p) },
            onManage = { managingPrompts = true },
            onDismiss = { askingAbout = null },
        )
    }
    if (managingPrompts) {
        PromptManagerSheet(prompts, onChange = { vm.savePrompts(it) }, onDismiss = { managingPrompts = false })
    }
    openHighlight?.let { id ->
        state.highlights.firstOrNull { it.value.id == id }?.let { h ->
            ModalBottomSheet(onDismissRequest = { openHighlight = null }, containerColor = colors.background) {
                HighlightSheet(h.value, state.people, vm, onDone = { openHighlight = null })
            }
        }
    }
}

/** 收起工具栏时底部那一行：小小的页码（像 Kindle）。 */
private val PAGE_NUMBER_HEIGHT = 28.dp

/** 多久没翻页就不再让屏幕常亮 */
private const val KEEP_SCREEN_ON_MS = 10 * 60 * 1000L

@Composable
private fun PageNumber(state: ReaderState, locator: Locator?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(pageLabel(state, locator), style = QichiTheme.typography.numeral.copy(fontSize = 11.tsp, color = QichiTheme.colors.faint))
    }
}

private fun pageLabel(state: ReaderState, locator: Locator?): String {
    val mine = locator?.locations?.totalProgression ?: state.mine?.progress
    val position = locator?.locations?.position
    return when {
        position != null && state.totalPositions > 0 -> "$position / ${state.totalPositions}"
        position != null -> "$position"
        mine != null -> "${(mine * 100).toInt()}%"
        else -> ""
    }
}

/** 叫出工具栏时的底部（按 New-Reading）：一条细进度条，我读到的部分是玫瑰色，上面两个人的标记在各自读到的位置；下面居中页码。 */
@Composable
private fun ProgressTrack(state: ReaderState, locator: Locator?, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val mine = locator?.locations?.totalProgression ?: state.mine?.progress
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 30.dp, end = 30.dp, top = Spacing.m, bottom = Spacing.s)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp).semantics(mergeDescendants = true) {
            contentDescription = buildString {
                mine?.let { append("我读到 ${(it * 100).toInt()}%") }
                state.partner?.let { append("，${people.name(it.userId)}读到 ${(it.progress * 100).toInt()}%") }
            }
        }) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).background(colors.ink.copy(alpha = .1f), QichiShapes.pill))
            mine?.let { Box(Modifier.align(Alignment.CenterStart).width(maxWidth * it.toFloat().coerceIn(0f, 1f)).height(4.dp).background(colors.personA, QichiShapes.pill)) }
            state.partner?.let { p ->
                PersonMark(people.markChar(p.userId), people.person(p.userId), size = 18.dp,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 18.dp) * p.progress.toFloat().coerceIn(0f, 1f)))
            }
            if (mine != null && people.myUserId != null) {
                PersonMark(people.markChar(people.myUserId), people.person(people.myUserId), size = 18.dp,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 18.dp) * mine.toFloat().coerceIn(0f, 1f)))
            }
        }
        Text(
            pageLabel(state, locator),
            style = type.numeral.copy(fontSize = 13.tsp, color = colors.muted),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.s),
        )
    }
}

@Composable
private fun TocSheet(state: ReaderState, locator: Locator?, onOpen: (org.readium.r2.shared.publication.Link) -> Unit, onNotes: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    // 正在读的那一章标出来，打开时就滚到它附近
    val current = remember(state.toc, locator?.href) { currentTocIndex(state.toc.map { it.link.href.toString() }, locator?.href?.toString()) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("目录", modifier = Modifier.weight(1f))
            TextAction("书签与笔记", onNotes)
        }
        LazyColumn(Modifier.heightIn(max = 480.dp), state = list) {
            itemsIndexed(state.toc) { i, item ->
                val reading = i == current
                Text(
                    item.link.title ?: item.link.href.toString(),
                    style = type.body.copy(
                        fontSize = (if (item.depth == 0) 16 else 14).tsp,
                        color = if (reading) colors.accent else if (item.depth == 0) colors.ink else colors.muted,
                        fontWeight = if (reading) FontWeight.W600 else null,
                    ),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onOpen(item.link) }
                        .semantics { if (reading) stateDescription = "正在读" }
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
    // 往下滑、点外面关掉时，写了的感想照样存下（以前只有点「保存」才存，一滑就丢）
    // 点了「保存」「删掉」的就不再补存（不是界面状态，不用触发重画）
    val closed = remember { booleanArrayOf(false) }
    val latest by rememberUpdatedState(note to shared)
    DisposableEffect(h.id) {
        onDispose {
            val (n, sh) = latest
            if (mine && h.kind != HighlightKind.Ai && !closed[0] && (n != h.note.orEmpty() || sh != h.shared)) vm.updateHighlight(h, n, sh)
        }
    }
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
            h.note?.let { MarkdownView(it, type.body.fontSize, 1.7f, style = type.body.copy(color = colors.ink), blockGap = 8.dp) }
            if (mine) {
                SwitchRow("共同可见", shared, { shared = it; vm.updateHighlight(h, h.note, it) })
                TextAction("删除", { deleting = true }, color = colors.muted)
            }
        } else if (mine) {
            QichiTextField(note, { note = it.take(Limits.HIGHLIGHT_NOTE_MAX) }, label = "感想（可以不写）", singleLine = false)
            SwitchRow("共同可见", shared, { shared = it }, description = "打开后${people.partner?.displayName ?: "对方"}也能看到这条和你的感想")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextAction("保存", { closed[0] = true; vm.updateHighlight(h, note, shared); onDone() })
                TextAction("删除", { deleting = true }, color = colors.muted)
            }
        } else {
            h.note?.let { Text(it, style = type.body.copy(color = colors.ink)) } ?: Text("没有写感想。", style = type.caption.copy(color = colors.muted))
        }
        Spacer(Modifier.height(Spacing.s))
    }
    if (deleting) {
        ConfirmDialog("删掉这条${h.kind.label}？", "删掉后不能恢复。", "删掉", onConfirm = { deleting = false; closed[0] = true; vm.deleteHighlight(h); onDone() }, onDismiss = { deleting = false })
    }
}

/** 沉浸阅读时收起状态栏和导航栏（从边缘滑一下会临时出现）；离开阅读页时恢复。 */
@Composable
private fun SystemBarsVisible(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(visible) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(controller) { onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) } }
}

/** 书内阅读的字号、行距、页边距、翻页方式（P20-01），只影响这台手机。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingSettingsSheet(settings: ReadingSettings, onChange: (ReadingSettings) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text("只影响这台手机上的显示。", style = type.caption.copy(color = colors.muted))
        SectionLabel("字号")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ReadingSettings.FONT_SCALES.zip(listOf("小", "标准", "大", "特大")).forEach { (v, label) ->
                ChoicePill(label, settings.fontScale == v, { onChange(settings.copy(fontScale = v)) })
            }
        }
        SectionLabel("行距")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ReadingSettings.LINE_HEIGHTS.zip(listOf("照原书", "适中", "宽松")).forEach { (v, label) ->
                ChoicePill(label, settings.lineHeight == v, { onChange(settings.copy(lineHeight = v)) })
            }
        }
        SectionLabel("页边距")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ReadingSettings.MARGINS.zip(listOf("窄", "标准", "宽")).forEach { (v, label) ->
                ChoicePill(label, settings.margins == v, { onChange(settings.copy(margins = v)) })
            }
        }
        SectionLabel("翻页")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ChoicePill("左右翻页", !settings.scroll, { onChange(settings.copy(scroll = false)) })
            ChoicePill("上下滚动", settings.scroll, { onChange(settings.copy(scroll = true)) })
        }
        SwitchRow("音量键翻页", settings.volumeKeys, { onChange(settings.copy(volumeKeys = it)) }, description = "音量减下一页，音量加上一页")
        Spacer(Modifier.height(Spacing.l))
    }
}
