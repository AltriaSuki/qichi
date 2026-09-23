package app.qichi.feature.writing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.WritingSettings
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.Markdown
import app.qichi.core.ui.MarkdownView
import kotlinx.coroutines.launch
import java.util.UUID

private enum class EditorMode { Edit, History, Rebase }

/**
 * 共同写作的编辑器（按 Writing.dc.html）：返回条（标题、预览、专注、更多）、基线落后时的提示条、
 * 纸面编辑区（Markdown 标记淡色显示）、底栏（字数与阅读时长、保存状态、字号行距、「存为 vN」）。
 * 专注模式只留纸面；历史版本与重基线在同一页里切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var menu by remember { mutableStateOf(false) }
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
    LaunchedEffect(state.text, state.ready) {
        if (state.ready && !vm.hasLocalEdits() && state.text != field.text) {
            field = TextFieldValue(state.text, TextRange(minOf(field.selection.start, state.text.length)))
        }
    }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val settings = state.settings
    val fontSize = settings.fontSize.tsp
    val doc = state.document?.value

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        if (focus) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextAction("退出专注", { focus = false }, color = colors.muted)
            }
        } else {
            // ── 返回条 ──
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 70.dp).padding(start = 8.dp, end = 6.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(QichiIcons.Back, "返回", onBack)
                Text(
                    doc?.title.orEmpty(),
                    style = type.pageTitle.copy(fontSize = 19.tsp, fontWeight = FontWeight.W300, letterSpacing = 0.2.em, color = colors.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "改标题") { renaming = true }.semantics { heading() },
                )
                IconAction(if (preview) QichiIcons.Pen else QichiIcons.Eye, if (preview) "回到编辑" else "预览", { preview = !preview })
                IconAction(QichiIcons.Focus, "专注", { focus = true })
                Box {
                    IconAction(QichiIcons.More, "更多", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.paper) {
                        DropdownMenuItem(text = { Text("大纲", style = type.body) }, onClick = { menu = false; showOutline = true })
                        DropdownMenuItem(text = { Text("历史版本", style = type.body) }, onClick = { menu = false; mode = EditorMode.History })
                        DropdownMenuItem(text = { Text("改标题", style = type.body) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("删除", style = type.body.copy(color = colors.accent)) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
            // ── 基线落后 ──
            if (state.conflict) {
                val author = doc?.latestAuthorId
                Row(
                    Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = 14.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp)).background(colors.personB.copy(alpha = 0.13f))
                        .padding(start = Spacing.m, end = Spacing.xs, top = 4.dp, bottom = 4.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${state.people.name(author)}存了 v${state.latestVersion}，你的内容基于 v${state.baseVersion}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (author != null) PersonMark(state.people.markChar(author), state.people.person(author), size = 18.dp)
                    Text("v${state.latestVersion}", style = type.numeral.copy(fontSize = 19.tsp, color = colors.personB))
                    Spacer(Modifier.weight(1f))
                    TextAction("重基线", { mode = EditorMode.Rebase }, color = colors.ink)
                }
            }
        }

        // ── 纸面 ──
        Box(
            Modifier.weight(1f).padding(horizontal = Spacing.m).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.paper),
        ) {
            when {
                state.ready -> Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        // 点纸面空白处：光标放到末尾，接着写
                        .clickable(interactionSource = null, indication = null, enabled = !preview) {
                            field = field.copy(selection = TextRange(field.text.length))
                            runCatching { focusRequester.requestFocus() }
                        }
                        .padding(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 20.dp),
                ) {
                    if (preview) {
                        MarkdownView(field.text, fontSize, settings.lineHeight)
                    } else {
                        val headingSize = fontSize * 1.3f
                        val markerColor = colors.faint
                        val transformation = remember(markerColor, headingSize) {
                            VisualTransformation { text -> TransformedText(Markdown.highlight(text.text, markerColor, headingSize), OffsetMapping.Identity) }
                        }
                        BasicTextField(
                            value = field,
                            onValueChange = { next ->
                                val changed = next.text != field.text
                                field = next
                                if (changed) vm.onTextChange(next.text)
                            },
                            textStyle = type.body.copy(
                                fontSize = fontSize, lineHeight = fontSize * settings.lineHeight, fontWeight = FontWeight.W300,
                                letterSpacing = 0.03.em, color = colors.ink,
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
        }

        // ── 底栏 ──
        if (!focus) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = Spacing.m, top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    val numeral = SpanStyle(fontFamily = type.numeral.fontFamily, fontSize = 19.tsp, color = colors.ink)
                    Text(
                        buildAnnotatedString {
                            withStyle(numeral) { append(formatCount(state.charCount)) }
                            append(" 字 · ")
                            withStyle(numeral) { append(state.minutes.toString()) }
                            append(" 分钟")
                        },
                        style = type.caption.copy(fontSize = 13.tsp, letterSpacing = 0.08.em, color = colors.muted),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val status = when {
                            state.saving -> "正在保存…"
                            state.conflict -> "对方有新版本"
                            state.unsaved -> "未保存"
                            state.latestVersion > 0 -> "已保存"
                            else -> "还没有保存"
                        }
                        if (state.unsaved || state.saving) Box(Modifier.size(6.dp).background(colors.personA, CircleShape))
                        Text(status, style = type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.14.em, color = colors.muted))
                    }
                }
                IconAction(QichiIcons.TextSize, "字号与行距", { showSettings = true })
                PrimaryButton("存为 v${state.latestVersion + 1}", vm::save, enabled = state.canSave)
            }
        }
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
