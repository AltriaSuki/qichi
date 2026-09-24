package app.qichi.feature.writing

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.SwitchRow
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.Ribbon
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.MarkdownView
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Document
import app.qichi.shared.model.DocCategory
import app.qichi.shared.model.DraftGenre
import app.qichi.shared.rules.Limits
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 共同写作的文稿列表。列表与编辑器互斥：点开一篇就进入编辑器，返回回到列表。 */
@Composable
fun DocumentListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    vm: DocumentListViewModel = hiltViewModel<DocumentListViewModel, DocumentListViewModel.Factory>(key = "docs-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var creating by rememberSaveable { mutableStateOf(false) }
    // 长按一篇：置顶、分类（P9-06）
    var organizing by remember { mutableStateOf<Document?>(null) }
    // AI 起草稿（P9-05）
    // 不用 rememberSaveable：用了草稿跳到编辑器再回来时，不该又弹出起草面板
    var drafting by remember { mutableStateOf(false) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Writing, onBack, actions = listOf(BarAction("请 AI 起草稿", QichiIcons.Spark, { drafting = true })))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
                if (state.loaded && state.documents.isEmpty()) {
                    Text("还没有文稿。一封信、一份清单、一篇两个人一起写的东西，都可以从这里开始。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                    TextAction("写一篇", { creating = true })
                } else if (state.documents.isNotEmpty()) {
                    // 搜索（标题在本机，正文要联网）和分类筛选（P9-06）
                    QichiTextField(state.query, vm::onQueryChange, label = "搜索", placeholder = "标题或正文里的字",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                    FlowRow(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoicePill("全部", state.category == null, { vm.onCategoryChange(null) })
                        DocCategory.entries.forEach { c -> ChoicePill(c.label(), state.category == c, { vm.onCategoryChange(c) }) }
                    }
                    if (state.bodySearchOffline) {
                        Text("离线时只搜标题。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.xs))
                    }
                    if (state.shown.isEmpty()) {
                        Text(if (state.query.isNotBlank()) "没有找到。" else "这个分类下还没有文稿。长按一篇文稿可以给它分类。",
                            style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                    }
                }
                state.shown.forEach { hit ->
                    val doc = hit.doc
                    DocumentRow(doc, doc.value.id in state.unsaved, state.people, state.zone, state.today, hit.snippet,
                        onClick = { onOpen(doc.value.id) }, onLongClick = { organizing = doc.value })
                }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("新文稿", { creating = true })
    }

    if (drafting || draft != null) {
        DraftSheet(
            today = state.today,
            draft = draft,
            onRequest = { genre, range -> vm.requestDraft(genre, range) },
            onUse = { drafting = false; vm.useDraft(onCreated = onOpen) },
            onDismiss = { vm.dismissDraft(); drafting = false },
        )
    }
    organizing?.let { target ->
        // 用最新的那份（改了以后面板里立刻反映）
        val doc = state.documents.firstOrNull { it.value.id == target.id }?.value ?: target
        OrganizeSheet(doc, onPinned = { vm.setPinned(doc, it) }, onCategory = { vm.setCategory(doc, it) }, onDismiss = { organizing = null })
    }
    if (creating) {
        TitleDialog(
            heading = "新文稿",
            initial = "",
            confirmLabel = "开始写",
            onDismiss = { creating = false },
            onConfirm = { title ->
                creating = false
                vm.create(title, onCreated = onOpen)
            },
        )
    }
}

internal fun DocCategory.label() = when (this) {
    DocCategory.Letter -> "信"
    DocCategory.Travel -> "游记"
    DocCategory.Diary -> "日记"
    DocCategory.Review -> "回顾"
    DocCategory.Other -> "其它"
}

/** 长按文稿：置顶（两个人看到的一样）、选一个分类。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OrganizeSheet(doc: Document, onPinned: (Boolean) -> Unit, onCategory: (DocCategory?) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).padding(bottom = Spacing.xl)) {
            SectionLabel(doc.title)
            SwitchRow("置顶", doc.pinned, onPinned)
            Text("分类", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.s))
            FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("不分类", doc.category == null, { onCategory(null) })
                DocCategory.entries.forEach { c -> ChoicePill(c.label(), doc.category == c, { onCategory(c) }) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    local: Local<Document>,
    unsaved: Boolean,
    people: People,
    zone: ZoneId,
    today: LocalDate,
    snippet: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val doc = local.value
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(role = Role.Button, onClickLabel = "打开文稿", onLongClickLabel = "置顶、分类", onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = Spacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(doc.title, style = type.feeling.copy(fontSize = 22.tsp, lineHeight = 30.tsp, color = colors.ink),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                doc.latestAuthorId?.let { PersonMark(people.markChar(it), people.person(it), size = 22.dp) }
            }
            Row(Modifier.padding(top = Spacing.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (doc.latestVersion > 0) Text("v${doc.latestVersion}", style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted))
                val meta = buildList {
                    if (doc.latestVersion == 0) add("还没有保存过") else add("${formatCount(doc.charCount)} 字")
                    add(relativeDay(doc.updatedAt.atZone(zone).toLocalDate(), today).first)
                }
                Text(meta.joinToString(" · "), style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                if (unsaved) {
                    Box(Modifier.size(6.dp).background(colors.accent, CircleShape))
                    Text("未保存", style = type.caption.copy(color = colors.muted))
                }
            }
            val meta = listOfNotNull(doc.category?.label(), if (doc.pinned) "置顶" else null)
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = type.caption.copy(color = colors.accent))
            if (snippet != null && snippet.isNotBlank()) {
                Text(snippet, style = type.preview.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xxs))
            }
        }
        // 置顶的挂一条书签带
        if (doc.pinned) Ribbon(Modifier.align(Alignment.TopEnd).padding(end = 44.dp), height = 22.dp)
    }
}

/**
 * AI 起草稿（P9-05）：选体裁和时间范围 → AI 参考那段时间的房间资料写一份草稿 → 先看一眼，
 * 点「用它开始写」才建文稿（草稿作为还没保存的内容，自己保存了才是 v1），点「不用」什么都不留下。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DraftSheet(
    today: LocalDate,
    draft: DraftRequest?,
    onRequest: (DraftGenre, DraftRanges.Range) -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val ranges = remember(today) { DraftRanges.presets(today) }
    var genre by rememberSaveable { mutableStateOf(DraftGenre.Travel) }
    var rangeIndex by rememberSaveable { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).padding(bottom = Spacing.xl)) {
            SectionLabel("AI 起草稿")
            val result = draft?.result
            when {
                draft == null -> {
                    Text("AI 会参考那段时间里记下的聊天、日程、决定、灵感、心情写一份草稿，你们再自己改。只参考你允许 AI 看的内容。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.xs))
                    Text("写什么", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = Spacing.xs)) {
                        DraftGenre.entries.forEach { g -> ChoicePill(g.label(), genre == g, { genre = g }) }
                    }
                    Text("哪段时间", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = Spacing.xs)) {
                        ranges.forEachIndexed { i, r ->
                            ChoicePill("${r.label} · ${r.start.monthValue}/${r.start.dayOfMonth}–${r.end.monthValue}/${r.end.dayOfMonth}", rangeIndex == i, { rangeIndex = i })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextAction("取消", onDismiss, color = colors.muted)
                        Spacer(Modifier.width(Spacing.s))
                        PrimaryButton("起草", { onRequest(genre, ranges[rangeIndex]) })
                    }
                }
                result == null -> {
                    Text("正在写「${draft.genre.label()}」（${draft.range.label}）…… 大概要半分钟。", style = type.body.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.m))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("取消", onDismiss, color = colors.muted) }
                }
                else -> {
                    Text("这是草稿，用了以后可以随便改；保存了才算你的第一版。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.xs))
                    Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(QichiShapes.card).background(colors.paper).verticalScroll(rememberScrollState()).padding(Spacing.m)) {
                        MarkdownView(result, 15.tsp, 1.8f)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextAction("不用", onDismiss, color = colors.muted)
                        Spacer(Modifier.width(Spacing.s))
                        PrimaryButton("用它开始写", onUse)
                    }
                }
            }
        }
    }
}

/** 1286 → 1,286 */
internal fun formatCount(n: Int): String = "%,d".format(n)

@Composable
internal fun TitleDialog(heading: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var title by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
        title = { Text(heading, style = type.pageTitle.copy(color = colors.ink)) },
        text = {
            QichiTextField(title, { title = it.take(Limits.DOCUMENT_TITLE_LENGTH.last) }, label = "标题", placeholder = "比如：给明年秋天的信",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        },
        confirmButton = { TextAction(confirmLabel, { onConfirm(title) }, enabled = title.isNotBlank()) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}
