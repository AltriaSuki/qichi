package app.qichi.feature.writing

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.WritingSettings
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.Ribbon
import app.qichi.core.designsystem.component.decor.ruledPaper
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.FileUrls
import app.qichi.core.ui.BlockComments
import app.qichi.core.ui.EditHistory
import app.qichi.core.ui.ImageViewer
import app.qichi.core.ui.Markdown
import app.qichi.core.ui.MarkdownEdits
import app.qichi.core.ui.MarkdownView
import app.qichi.shared.model.WriteAssistMode
import app.qichi.shared.rules.DocumentImages
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.Authorship
import coil3.compose.AsyncImage
import java.util.UUID
import kotlinx.coroutines.launch

private enum class EditorMode { Edit, History, Rebase }

/**
 * 共同写作的编辑器（按 Writing.dc.html）：返回条（标题、预览、专注、更多）、基线落后时的提示条、
 * 纸面编辑区（Markdown 标记淡色显示）、底栏（字数与阅读时长、保存状态、字号行距、「存为 vN」）。
 * 专注模式只留纸面；历史版本与重基线在同一页里切换。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DocumentEditorScreen(
    roomId: UUID,
    documentId: UUID,
    onBack: () -> Unit,
    vm: DocumentEditorViewModel = hiltViewModel<DocumentEditorViewModel, DocumentEditorViewModel.Factory>(key = "doc-$documentId") {
        it.create(roomId, documentId)
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(EditorMode.Edit) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 文稿被删除（本机或对方）：回到列表
    LaunchedEffect(state.loaded, state.document) { if (state.loaded && state.document == null) onBack() }

    when (mode) {
        EditorMode.History -> {
            HistoryView(state, vm, onBack = { mode = EditorMode.Edit }, onRestored = { mode = EditorMode.Edit })
            return
        }
        EditorMode.Rebase -> {
            RebaseView(state, onBack = { mode = EditorMode.Edit }, onKeepMine = { vm.keepMine(); mode = EditorMode.Edit },
                onTakeLatest = { vm.takeLatest(); mode = EditorMode.Edit })
            return
        }
        EditorMode.Edit -> Unit
    }
    BackHandler(enabled = focus) { focus = false }

    // 编辑框自己持有内容与光标；只在本机没有正在输入时跟随外部变化（取到最新版本、对方保存、重基线）
    var field by remember(documentId) { mutableStateOf(TextFieldValue(state.text)) }
    // 撤销 / 重做（P9-01），每篇文稿各自一份；[historyTick] 让按钮的可用状态跟着刷新
    val history = remember(documentId) { EditHistory() }
    var historyTick by remember(documentId) { mutableIntStateOf(0) }
    // 拼音输入法正在组词时，开始组词前的样子；组完成一步记进撤销
    var composingFrom by remember(documentId) { mutableStateOf<EditHistory.State?>(null) }
    fun TextFieldValue.snapshot() = EditHistory.State(text, selection.min, selection.max)
    fun setText(next: TextFieldValue) {
        field = next
        vm.onTextChange(next.text)
        historyTick++
    }
    /** 格式按钮、勾选框：单独记一步。 */
    fun applyEdit(edit: MarkdownEdits.Edit) {
        val next = TextFieldValue(edit.text, TextRange(edit.start, edit.end))
        history.record(field.snapshot(), next.snapshot(), force = true)
        setText(next)
    }
    fun restore(to: EditHistory.State?) {
        to ?: return
        composingFrom = null
        setText(TextFieldValue(to.text, TextRange(to.start.coerceAtMost(to.text.length), to.end.coerceAtMost(to.text.length))))
    }
    // 插照片（P9-02）：选图 → 上传 → 在光标所在行后面插一行图片标记
    val uploadingImage by vm.uploadingImage.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.uploadImage(uri) { fileId ->
                applyEdit(MarkdownEdits.insertBlock(MarkdownEdits.Edit(field.text, field.selection.min, field.selection.max), DocumentImages.markdown(fileId)))
            }
        }
    }
    var viewingImage by remember { mutableStateOf<UUID?>(null) }
    // 段落旁留言（P9-03）：按当前正文找回每条讨论的位置
    val comments by vm.comments.collectAsStateWithLifecycle()
    val blocks = remember(field.text) { Markdown.parse(field.text) }
    val threads = remember(comments, blocks) { commentThreads(comments, blocks) }
    val threadsByBlock = remember(threads) { threads.filter { it.block != null }.groupBy { it.block!! } }
    var newCommentQuote by remember { mutableStateOf<String?>(null) }
    // 写作助手（P9-04 / P9-05）
    val assist by vm.assist.collectAsStateWithLifecycle()
    /** 用 AI 的建议换掉原来那段（原文被改过就在正文里重新找；找不到就不换）。 */
    fun useAssist(a: AssistState, result: String) {
        val t = field.text
        val start = if (a.end <= t.length && t.substring(a.start, a.end) == a.original) a.start else t.indexOf(a.original)
        if (start < 0) {
            Toast.makeText(context, "原文已经改了，没法替换", Toast.LENGTH_SHORT).show()
            return
        }
        applyEdit(MarkdownEdits.Edit(t.substring(0, start) + result + t.substring(start + a.original.length), start, start + result.length))
    }
    // 打开的留言列表：某一块的下标，或 [ALL_COMMENTS] 全部
    var showThreads by remember { mutableStateOf<Int?>(null) }
    viewingImage?.let { id -> ImageViewer(id, vm.urls, onDismiss = { viewingImage = null }) }
    LaunchedEffect(state.text, state.ready) {
        if (state.ready && !vm.hasLocalEdits() && state.text != field.text) {
            field = TextFieldValue(state.text, TextRange(minOf(field.selection.start, state.text.length)))
            history.clear()
            historyTick++
        }
    }
    // 署名（P10-08）：存过的版本算出的底子，再接上编辑框里还没存的改动（算我的）
    val authorBase by vm.authorship.collectAsStateWithLifecycle()
    LaunchedEffect(state.settings.showAuthorship, state.latestVersion) { if (state.settings.showAuthorship) vm.loadAuthorship() }
    val me = state.people.myUserId
    val liveRuns = remember(authorBase, field.text, me) {
        val base = authorBase
        if (base == null || base.failed || me == null) emptyList() else Authorship.extend(base.runs, field.text, me)
    }
    val authorCounts = remember(liveRuns) { Authorship.counts(liveRuns) }
    val partnerRanges = remember(liveRuns, me) {
        var at = 0
        liveRuns.mapNotNull { r -> val range = at until at + r.text.length; at += r.text.length; range.takeIf { r.authorId != me } }
    }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val settings = state.settings
    val fontSize = settings.fontSize.tsp
    val doc = state.document?.value

    // 顶栏收窄（P12-02）：键盘打开、或手指往下滑时收成窄条；往上滑回来时展开
    val imeVisible = WindowInsets.isImeVisible
    var scrolledDown by remember { mutableStateOf(false) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) scrolledDown = true else if (available.y > 6f) scrolledDown = false
                return Offset.Zero
            }
        }
    }
    val compactBar = (imeVisible || scrolledDown) && !focus

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        run {
            // ── 顶栏（专注时也在：功能名 + 标题；右边换成「退出专注」） ──
            val openThreads = threads.count { !it.resolved }
            val authorshipOn = settings.showAuthorship
            ItemTopBar(
                doc?.title.orEmpty(), onBack, feature = Feature.Writing,
                modifier = if (QichiTheme.reduceMotion) Modifier else Modifier.animateContentSize(),
                compact = compactBar,
                actions = if (focus) {
                    listOf(BarAction("退出专注", QichiIcons.Focus, { focus = false }, tint = colors.accent))
                } else {
                    listOf(
                        BarAction(if (authorshipOn) "关掉署名" else "署名", QichiIcons.People, { vm.setAuthorship(!authorshipOn) }, tint = if (authorshipOn) colors.accent else null),
                        BarAction(if (preview) "回到编辑" else "预览", if (preview) QichiIcons.Pen else QichiIcons.Eye, { preview = !preview }),
                    )
                },
                menu = listOfNotNull(
                    if (focus) null else MenuAction("专注模式", { focus = true }),
                    MenuAction("大纲", { showOutline = true }),
                    MenuAction("帮我起标题", {
                        if (field.text.isBlank()) {
                            Toast.makeText(context, "先写点内容再起标题", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.requestAssist(WriteAssistMode.Titles, field.text.take(Limits.WRITE_TITLES_TEXT_MAX), 0, 0)
                        }
                    }),
                    MenuAction(if (openThreads > 0) "留言（$openThreads 条没解决）" else "留言", { showThreads = ALL_COMMENTS }),
                    MenuAction("历史版本", { mode = EditorMode.History }),
                    MenuAction("改标题", { renaming = true }),
                    MenuAction("删除", { deleting = true }, danger = true),
                ),
            )
            // ── 对方先存了新版（按 New-Writing-Behind）：提示卡，存按钮变灰 ──
            if (state.conflict && !focus) {
                val author = doc?.latestAuthorId
                Column(
                    Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = 14.dp).fillMaxWidth()
                        .lift(colors).clip(QichiShapes.card).background(colors.card).background(colors.personB.copy(alpha = .1f))
                        .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        FeatureTile(QichiIcons.Refresh, colors.personB, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildAnnotatedString {
                                    append("${state.people.name(author)}刚存了 ")
                                    withStyle(SpanStyle(fontFamily = type.numeral.fontFamily)) { append("v${state.latestVersion}") }
                                },
                                style = type.body.copy(fontWeight = FontWeight.W600, color = colors.ink),
                            )
                            Text(
                                buildAnnotatedString {
                                    append("你在 ")
                                    withStyle(SpanStyle(fontFamily = type.numeral.fontFamily)) { append("v${state.baseVersion}") }
                                    append(" 上改的还在，先合到新版再存")
                                },
                                style = type.caption.copy(color = colors.muted),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextAction("看看改了什么", { mode = EditorMode.Rebase }, color = colors.muted)
                        PrimaryButton("重基线", { mode = EditorMode.Rebase })
                    }
                }
            }
            // ── 署名图例：对方写了多少、我写了多少 ──
            if (settings.showAuthorship && !focus && !preview && !compactBar) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.page, end = Spacing.page, top = 2.dp, bottom = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    val numeral = SpanStyle(fontFamily = type.numeral.fontFamily, color = colors.ink)
                    val base = authorBase
                    when {
                        base == null -> Text("正在算谁写了什么…", style = type.caption.copy(color = colors.muted))
                        base.failed -> Text("离线时取不到旧版本，暂时看不了署名", style = type.caption.copy(color = colors.muted))
                        else -> {
                            val partnerId = state.people.partner?.userId
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Box(Modifier.size(22.dp, 12.dp).background(colors.personB.copy(alpha = .16f), RoundedCornerShape(3.dp)).drawBehind {
                                    drawRect(colors.personB.copy(alpha = .55f), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                                })
                                Text(buildAnnotatedString {
                                    append("${state.people.name(partnerId).ifEmpty { "对方" }}写的 ")
                                    withStyle(numeral) { append(formatCount(authorCounts[partnerId] ?: 0)) }
                                    append(" 字")
                                }, style = type.caption.copy(color = colors.muted))
                            }
                            Text(buildAnnotatedString {
                                append("我写的 ")
                                withStyle(numeral) { append(formatCount(authorCounts[state.people.myUserId] ?: 0)) }
                                append(" 字")
                            }, style = type.caption.copy(color = colors.muted))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("v${state.latestVersion}", style = type.numeral.copy(fontSize = 14.tsp, color = colors.muted))
                }
            }
        }

        // ── 纸面 ──
        val lineDp = with(LocalDensity.current) { (settings.fontSize * type.scale * settings.lineHeight).sp.toDp() }
        // 纸面铺到屏幕两边（P12-02），没有留边和阴影；页边线离左边 [PAPER_MARGIN]
        Box(
            Modifier.weight(1f).fillMaxWidth().nestedScroll(barScroll)
                .then(if (preview) Modifier.background(colors.paper) else Modifier.ruledPaper(lineDp, top = 24.dp, marginX = PAPER_MARGIN)),
        ) {
            when {
                state.ready -> Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        // 点纸面空白处：光标放到末尾，接着写
                        .clickable(interactionSource = null, indication = null, enabled = !preview) {
                            field = field.copy(selection = TextRange(field.text.length))
                            runCatching { focusRequester.requestFocus() }
                        }
                        // 底部留出浮着的底栏的高度，最后几行能滚到它上面
                        .padding(start = if (preview) Spacing.page else PAPER_MARGIN + 12.dp, end = Spacing.page, top = 24.dp, bottom = if (imeVisible || focus) 20.dp else FLOATING_BAR_SPACE),
                ) {
                    if (preview) {
                        MarkdownView(
                            field.text, fontSize, settings.lineHeight,
                            onToggleTask = { line ->
                                val toggled = MarkdownEdits.toggleTask(field.text, line)
                                applyEdit(MarkdownEdits.Edit(toggled, field.selection.min.coerceAtMost(toggled.length)))
                            },
                            image = { fileId, alt -> DocumentImage(fileId, alt, vm.urls, onOpen = { viewingImage = fileId }) },
                            comments = BlockComments(
                                badge = { i -> threadsByBlock[i]?.let { list -> list.size to list.all { it.resolved } } },
                                canComment = { CommentAnchors.text(it) != null },
                                onOpen = { i -> showThreads = i },
                                onLongPress = { i -> newCommentQuote = blocks.getOrNull(i)?.let(CommentAnchors::text)?.take(Limits.DOC_COMMENT_QUOTE_MAX) },
                            ),
                        )
                    } else {
                        val headingSize = fontSize * 1.25f
                        val markerColor = colors.faint
                        val markerFont = type.numeral.fontFamily
                        val shade = colors.personB.copy(alpha = .16f)
                        val dim = colors.faint
                        val glow = colors.accent.copy(alpha = .08f)
                        val partnerRanges = if (settings.showAuthorship) partnerRanges else emptyList()
                        val current = if (focus) currentLine(field.text, field.selection.start) else null
                        val transformation = remember(markerColor, headingSize, partnerRanges, current, dim) {
                            VisualTransformation { text ->
                                val styled = buildAnnotatedString {
                                    append(Markdown.highlight(text.text, markerColor, headingSize, markerFont))
                                    partnerRanges.forEach { r -> if (r.last < text.length) addStyle(SpanStyle(background = shade), r.first, r.last + 1) }
                                    if (current != null) {
                                        if (current.first > 0) addStyle(SpanStyle(color = dim), 0, current.first)
                                        if (current.last + 1 < text.length) addStyle(SpanStyle(color = dim), current.last + 1, text.length)
                                        if (!current.isEmpty()) addStyle(SpanStyle(background = glow), current.first, current.last + 1)
                                    }
                                }
                                TransformedText(styled, OffsetMapping.Identity)
                            }
                        }
                        BasicTextField(
                            value = field,
                            onValueChange = { next ->
                                val changed = next.text != field.text
                                if (changed) {
                                    if (next.composition != null) {
                                        if (composingFrom == null) composingFrom = field.snapshot()
                                    } else {
                                        history.record(composingFrom ?: field.snapshot(), next.snapshot())
                                        composingFrom = null
                                    }
                                    setText(next)
                                } else {
                                    field = next
                                }
                            },
                            textStyle = type.body.copy(
                                fontSize = fontSize, lineHeight = settings.lineHeight.em, fontWeight = FontWeight.W400,
                                color = colors.ink,
                            ),
                            cursorBrush = SolidColor(colors.personA),
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = "正文" },
                            decorationBox = { inner ->
                                if (field.text.isEmpty()) Text("从这里开始写。可以用 # 写标题，用 - 列清单。", style = type.body.copy(fontSize = fontSize, color = colors.faint))
                                inner()
                            },
                        )
                    }
                }
                state.needsNetwork -> Column(Modifier.align(Alignment.Center).padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("需要联网才能取回 v${state.latestVersion}", style = type.body.copy(color = colors.muted))
                    TextAction("再试一次", vm::retryLoad)
                }
                else -> Text("正在打开…", style = type.caption.copy(color = colors.faint), modifier = Modifier.align(Alignment.Center))
            }
            // ── 底栏浮在纸面上（P12-02）：左边字数与保存状态一行，右边字号和「存为 vN」；键盘开着时让给格式按钮 ──
            if (!imeVisible && !focus) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(0f to colors.paper.copy(alpha = 0f), .35f to colors.paper.copy(alpha = .92f), 1f to colors.paper))
                        .navigationBarsPadding().padding(start = PAPER_MARGIN + 12.dp, end = Spacing.m, top = Spacing.m, bottom = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val numeral = SpanStyle(fontFamily = type.numeral.fontFamily, fontSize = 15.tsp, color = colors.ink)
                    val status = when {
                        state.saving -> "正在保存…"
                        state.conflict -> "对方有新版本"
                        state.unsaved -> "未保存"
                        state.latestVersion > 0 -> "已保存"
                        else -> "还没有保存"
                    }
                    if (state.unsaved || state.saving) Box(Modifier.size(6.dp).background(colors.personA, CircleShape))
                    Text(
                        buildAnnotatedString {
                            withStyle(numeral) { append(formatCount(state.charCount)) }
                            append(" 字 · ")
                            withStyle(numeral) { append(state.minutes.toString()) }
                            append(" 分钟 · $status")
                        },
                        style = type.caption.copy(fontSize = 12.tsp, color = colors.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconAction(QichiIcons.TextSize, "字号与行距", { showSettings = true })
                    PrimaryButton("存为 v${state.latestVersion + 1}", vm::save, enabled = state.canSave)
                }
            }
            // 书签带只给置顶的文稿，贴在右上角
            if (doc?.pinned == true) Ribbon(Modifier.align(Alignment.TopEnd).padding(end = 22.dp), height = 40.dp)
        }

        // ── 格式按钮：键盘打开、正在编辑时代替底栏（保存放在这一排最右边） ──
        if ((imeVisible || focus) && !preview && state.ready) {
            key(historyTick) {
                FormatBar(
                    saveLabel = "存 v${state.latestVersion + 1}",
                    canSave = state.canSave,
                    onSave = vm::save,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    uploadingImage = uploadingImage,
                    canComment = !field.selection.collapsed,
                    onAssist = { mode ->
                        val sel = field.selection
                        val text = field.text.substring(sel.min, sel.max)
                        if (text.length > Limits.WRITE_ASSIST_TEXT_MAX) {
                            Toast.makeText(context, "一次最多选 ${Limits.WRITE_ASSIST_TEXT_MAX} 字", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.requestAssist(mode, text, sel.min, sel.max)
                        }
                    },
                    onComment = {
                        newCommentQuote = field.text.substring(field.selection.min, field.selection.max).trim().take(Limits.DOC_COMMENT_QUOTE_MAX).ifEmpty { null }
                    },
                    onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onEdit = { transform ->
                        val e = MarkdownEdits.Edit(field.text, field.selection.min, field.selection.max)
                        applyEdit(transform(e))
                    },
                    onUndo = { restore(history.undo(field.snapshot())) },
                    onRedo = { restore(history.redo(field.snapshot())) },
                )
            }
        }
    }

    assist?.let { a ->
        AssistSheet(
            a,
            onUse = { result ->
                if (a.mode == WriteAssistMode.Titles) vm.rename(result) else useAssist(a, result)
                vm.dismissAssist()
            },
            onDismiss = vm::dismissAssist,
        )
    }
    newCommentQuote?.let { quote ->
        NewCommentSheet(quote, onSubmit = { body -> vm.addComment(quote, body); newCommentQuote = null }, onDismiss = { newCommentQuote = null })
    }
    showThreads?.let { which ->
        CommentsSheet(
            title = if (which == ALL_COMMENTS) "留言" else "这一段的留言",
            threads = if (which == ALL_COMMENTS) threads else threadsByBlock[which].orEmpty(),
            people = state.people,
            onReply = vm::reply,
            onResolve = vm::setResolved,
            onDelete = vm::deleteComment,
            onDismiss = { showThreads = null },
        )
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background) {
            WritingSettingsSheet(settings, vm::setSettings)
        }
    }
    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }, containerColor = colors.background) {
            val headings = remember(field.text) { Markdown.headings(field.text) }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
                SectionLabel("大纲")
                if (headings.isEmpty()) Text("还没有标题。用 # 开头的一行就是标题。", style = type.caption.copy(color = colors.muted))
                headings.forEach { h ->
                    Text(
                        h.text,
                        style = type.body.copy(fontSize = (if (h.level <= 2) 17 else 15).tsp, color = colors.ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) {
                            showOutline = false
                            preview = false
                            val offset = field.text.lineSequence().take(h.line).sumOf { it.length + 1 }.coerceAtMost(field.text.length)
                            field = field.copy(selection = TextRange(offset))
                            layout?.let { l -> scope.launch { scroll.animateScrollTo(l.getLineTop(l.getLineForOffset(offset)).toInt()) } }
                        }.padding(start = ((h.level - 1).coerceAtMost(3) * 14).dp, top = 10.dp),
                    )
                }
                Spacer(Modifier.heightIn(min = Spacing.l))
            }
        }
    }
    if (renaming && doc != null) {
        TitleDialog("改标题", doc.title, "保存", onDismiss = { renaming = false }, onConfirm = { vm.rename(it); renaming = false })
    }
    if (deleting) {
        ConfirmDialog("删除这篇文稿？", "连同所有版本进回收站，可以恢复。", "删除", onConfirm = { deleting = false; vm.delete() }, onDismiss = { deleting = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
private const val ALL_COMMENTS = -1

/** 文稿预览里的一张照片：按宽度铺满，点开看大图。 */
@Composable
internal fun DocumentImage(fileId: UUID, alt: String, urls: FileUrls, onOpen: () -> Unit) {
    val colors = QichiTheme.colors
    AsyncImage(
        model = urls.thumbnail(fileId, 800),
        contentDescription = alt.ifBlank { "照片" },
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(QichiShapes.card).background(colors.line)
            .clickable(role = Role.Image, onClickLabel = "看大图", onClick = onOpen),
    )
}

/** 键盘上方的格式按钮（P9-01）：左边可以横着滑，撤销 / 重做固定在右边。 */
@Composable
private fun FormatBar(
    saveLabel: String,
    canSave: Boolean,
    onSave: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    uploadingImage: Boolean,
    onImage: () -> Unit,
    canComment: Boolean,
    onComment: () -> Unit,
    onAssist: (WriteAssistMode) -> Unit,
    onEdit: ((MarkdownEdits.Edit) -> MarkdownEdits.Edit) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val colors = QichiTheme.colors
    var assistChoices by remember { mutableStateOf(false) }
    if (assistChoices && canComment) {
        // AI 的几种帮法，代替格式按钮这一行
        Row(
            Modifier.fillMaxWidth().background(colors.background).padding(horizontal = Spacing.s, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("AI", style = QichiTheme.typography.numeral.copy(fontSize = 18.tsp, color = colors.personB), modifier = Modifier.padding(horizontal = Spacing.xs))
            listOf(WriteAssistMode.Polish, WriteAssistMode.Proofread, WriteAssistMode.Shorten).forEach { mode ->
                TextAction(mode.label(), { assistChoices = false; onAssist(mode) }, color = colors.ink)
            }
            Spacer(Modifier.weight(1f))
            TextAction("取消", { assistChoices = false }, color = colors.muted)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = Spacing.s, bottom = Spacing.sm)
            .lift(colors, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(colors.card)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            // 选中文字时最前面多一个「留言」
            if (canComment) {
                IconAction(QichiIcons.Comment, "给选中的文字留言", onComment, tint = colors.accent)
                // 选中文字时：请 AI 润色、改错别字、缩短（P9-04）。不用弹出菜单——弹窗会抢走焦点、键盘一收格式栏就没了
                Box(
                    Modifier.size(Sizes.touchTarget).clickable(role = Role.Button, onClickLabel = "请 AI 帮忙") { assistChoices = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AI", style = QichiTheme.typography.numeral.copy(fontSize = 18.tsp, color = colors.personB))
                }
            }
            IconAction(QichiIcons.Heading, "标题", { onEdit(MarkdownEdits::cycleHeading) })
            IconAction(QichiIcons.Bold, "加粗", { onEdit(MarkdownEdits::toggleBold) })
            IconAction(QichiIcons.BulletList, "列表", { onEdit { MarkdownEdits.toggleLinePrefix(it, "- ") } })
            IconAction(QichiIcons.TaskList, "勾选框", { onEdit { MarkdownEdits.toggleLinePrefix(it, "- [ ] ") } })
            IconAction(QichiIcons.Quote, "引用", { onEdit { MarkdownEdits.toggleLinePrefix(it, "> ") } })
            IconAction(QichiIcons.Rule, "分隔线", { onEdit(MarkdownEdits::insertRule) })
            IconAction(
                QichiIcons.Image, if (uploadingImage) "正在传照片" else "插照片", onImage,
                enabled = !uploadingImage, tint = if (uploadingImage) colors.faint else colors.ink,
            )
        }
        Box(Modifier.width(1.dp).height(22.dp).background(colors.line))
        IconAction(QichiIcons.Undo, "撤销", onUndo, enabled = canUndo, tint = if (canUndo) colors.ink else colors.faint)
        IconAction(QichiIcons.Redo, "重做", onRedo, enabled = canRedo, tint = if (canRedo) colors.ink else colors.faint)
        // 保存（P12-02）：键盘开着时底栏收起，存按钮在这里
        Box(
            Modifier.heightIn(min = Sizes.touchTarget).padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.clip(QichiShapes.pill).background(if (canSave) colors.ink else colors.ink.copy(alpha = .12f))
                    .clickable(enabled = canSave, role = Role.Button, onClick = onSave)
                    .heightIn(min = 36.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(saveLabel, style = QichiTheme.typography.button.copy(fontSize = 14.tsp, color = if (canSave) colors.background else colors.faint))
            }
        }
    }
}

/** 纸面底部给浮着的底栏留的空白。 */
private val FLOATING_BAR_SPACE = 96.dp

/** 编辑器横线纸的页边线离左边多远（P12-02：纸面铺满后往左挪）。 */
private val PAPER_MARGIN = 28.dp

@Composable
private fun WritingSettingsSheet(settings: WritingSettings, onChange: (WritingSettings) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text("只影响这台手机上的显示。", style = type.caption.copy(color = colors.muted))
        SectionLabel("字号")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WritingSettings.FONT_SIZES.zip(listOf("小", "标准", "大", "特大")).forEach { (size, label) ->
                ChoicePill(label, settings.fontSize == size, { onChange(settings.copy(fontSize = size)) })
            }
        }
        SectionLabel("行距")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WritingSettings.LINE_HEIGHTS.zip(listOf("紧凑", "适中", "宽松")).forEach { (h, label) ->
                ChoicePill(label, settings.lineHeight == h, { onChange(settings.copy(lineHeight = h)) })
            }
        }
        Spacer(Modifier.heightIn(min = Spacing.l))
    }
}

/** 专注模式里「当前这一段」：光标所在的那一行（字符下标范围）。 */
internal fun currentLine(text: String, cursor: Int): IntRange {
    val at = cursor.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
    return start until end
}
