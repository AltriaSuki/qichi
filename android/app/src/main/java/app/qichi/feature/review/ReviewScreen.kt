package app.qichi.feature.review

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.data.REVIEW_MIME_TYPES
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.DiffView
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.FindingEvidence
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.DiffKind
import app.qichi.shared.api.NormRect
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ReviewFormat
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.Diff
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** 选中了什么（还没写批注）：一段文字、一个单元格、一块区域、一张图片或整页。 */
internal data class Selection(val page: Int, val kind: AnchorKind, val rect: NormRect?, val ref: String?, val quote: String?) {
    fun toAnchor() = AnnotationAnchor(page, kind, rect, ref, quote?.take(Limits.ANCHOR_QUOTE_MAX))
}

private val TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/**
 * 一份审稿（Review.dc.html）：上面是当前版本的安全预览（服务器渲染好的页面图片），批注用圈和序号标在页面上；
 * 点一段文字（表格里是一个格子）或长按拖出一块区域来批注、提修改；下面是批注列表，点开看讨论、接受或归档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    roomId: UUID,
    documentId: UUID,
    onBack: () -> Unit,
    vm: ReviewViewModel = hiltViewModel<ReviewViewModel, ReviewViewModel.Factory>(key = "review-$documentId") { it.create(roomId, documentId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val diff by vm.diff.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selection by remember { mutableStateOf<Selection?>(null) }
    var composing by remember { mutableStateOf<Pair<Selection, AnnotationKind>?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var versionSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var showResolved by rememberSaveable { mutableStateOf(false) }
    var showResolvedFindings by rememberSaveable { mutableStateOf(false) }
    var askingAi by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::uploadVersion) }

    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.messageShown() } }
    LaunchedEffect(state.version?.id) { selection = null }

    val doc = state.doc
    val version = state.version
    val readyPages = (pages as? PagesState.Ready)?.pages.orEmpty()
    val pagerState = rememberPagerState { max(readyPages.size, 1) }

    if (state.loaded && doc == null) {
        Column(Modifier.fillMaxSize().background(colors.background)) {
            BackBar("审稿", onBack)
            Text("这份审稿已经删除了。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(Spacing.page))
        }
        return
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar(doc?.title ?: "", onBack) {
            IconAction(QichiIcons.Plus, "传新版本", { picker.launch(REVIEW_MIME_TYPES) }, enabled = uploading == null && doc != null)
            IconAction(QichiIcons.More, "更多", { menu = true })
        }
        // 版本行：v3 · 对比 v2 · 2 / 5
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(
                version?.let { "v${it.version}" } ?: "",
                style = type.numeral.copy(fontSize = 24.tsp, color = colors.ink),
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "换版本") { versionSheet = true }
                    .semantics { contentDescription = "第 ${version?.version ?: 0} 版，点一下换版本" },
            )
            val previous = version?.let { v -> state.versions.lastOrNull { it.version < v.version && it.previewStatus == PreviewStatus.Ready } }
            if (previous != null && version.previewStatus == PreviewStatus.Ready) {
                TextAction("对比 v${previous.version}", { vm.compare(previous.version, version.version) }, color = colors.muted)
            }
            Spacer(Modifier.weight(1f))
            if (readyPages.isNotEmpty()) {
                Text("${pagerState.currentPage + 1} / ${readyPages.size}", style = type.numeral.copy(fontSize = 18.tsp, color = colors.muted))
            }
        }
        uploading?.let { Text("正在上传新版本 ${(it * 100).toInt()}%", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(horizontal = Spacing.page)) }

        // 预览
        Box(Modifier.weight(0.58f).fillMaxWidth().padding(horizontal = Spacing.page, vertical = Spacing.xs), contentAlignment = Alignment.Center) {
            when (val p = pages) {
                PagesState.Loading -> Text(
                    if (version?.previewStatus == PreviewStatus.Pending) "正在生成预览……Word、Excel、PowerPoint 要先转换，稍等一会儿" else "正在取预览……",
                    style = type.caption.copy(color = colors.muted), textAlign = TextAlign.Center,
                )
                is PagesState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.message, style = type.caption.copy(color = colors.muted), textAlign = TextAlign.Center)
                    if (version?.previewStatus == PreviewStatus.Ready) TextAction("再试一次", vm::retryPages)
                    if (version?.previewStatus == PreviewStatus.Failed) TextAction("传一个新版本", { picker.launch(REVIEW_MIME_TYPES) })
                }
                is PagesState.Ready -> HorizontalPager(pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                    val page = p.pages.getOrNull(index) ?: return@HorizontalPager
                    PageView(
                        page = page,
                        imageUrl = vm.urls.original(page.imageFileId),
                        format = version?.format ?: ReviewFormat.Pdf,
                        annotations = state.annotations.filter { it.value.anchor.page == page.page },
                        selection = selection?.takeIf { it.page == page.page },
                        highlighted = detailId,
                        onSelect = { selection = it },
                        onOpenAnnotation = { detailId = it.toString() },
                    )
                }
            }
        }

        // 选中后的操作；没选时给提示
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = Spacing.page), verticalAlignment = Alignment.CenterVertically) {
            val sel = selection
            if (sel != null) {
                Text(
                    sel.quote?.let { "“${it.take(40)}”" } ?: when (sel.kind) { AnchorKind.Image -> "这张图"; AnchorKind.Slide -> "这一页"; else -> "圈出的区域" },
                    style = type.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextAction("批注", { composing = sel to AnnotationKind.Comment })
                TextAction("提修改", { composing = sel to AnnotationKind.Proposal })
                TextAction("取消", { selection = null }, color = colors.muted)
            } else if (readyPages.isNotEmpty()) {
                Text(
                    if (version?.format == ReviewFormat.Sheet) "点一个格子，或长按拖出一块区域" else "点一段文字，或长按拖出一块区域",
                    style = type.caption.copy(color = colors.faint), modifier = Modifier.weight(1f),
                )
                val current = readyPages.getOrNull(pagerState.currentPage)
                if (current != null) {
                    TextAction(if (version?.format == ReviewFormat.Slides) "批注这张幻灯片" else "批注这一页", {
                        selection = Selection(current.page, AnchorKind.Slide, null, null, current.blocks.firstOrNull()?.text)
                    }, color = colors.muted)
                }
            }
        }

        // 批注 / AI
        Row(Modifier.padding(horizontal = Spacing.page), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            TabLabel("批注", tab == 0, dot = state.open.isNotEmpty()) { tab = 0 }
            TabLabel("AI", tab == 1, dot = state.newFindings.isNotEmpty()) { tab = 1 }
        }
        Column(Modifier.weight(0.42f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            if (tab == 0) {
                if (state.annotations.isEmpty()) {
                    Text("这一版还没有批注。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.s))
                }
                state.open.forEach { item ->
                    AnnotationRow(item, state.people) {
                        detailId = item.value.id.toString()
                        scope.launch { pagerState.animateScrollToPage((item.value.anchor.page - 1).coerceIn(0, max(readyPages.size - 1, 0))) }
                    }
                }
                if (state.resolved.isNotEmpty()) {
                    TextAction(if (showResolved) "收起已处理的" else "已处理的 ${state.resolved.size} 条", { showResolved = !showResolved }, color = colors.muted)
                    if (showResolved) state.resolved.forEach { item -> AnnotationRow(item, state.people) { detailId = item.value.id.toString() } }
                }
            } else {
                AiPanel(
                    state = state,
                    onAsk = { askingAi = true },
                    onEvidence = { e ->
                        selection = Selection(e.page, AnchorKind.Paragraph, e.rect, e.ref, e.quote)
                        scope.launch { pagerState.animateScrollToPage((e.page - 1).coerceIn(0, max(readyPages.size - 1, 0))) }
                    },
                    onDismiss = vm::dismiss,
                    onConvert = vm::convert,
                    showResolved = showResolvedFindings,
                    onToggleResolved = { showResolvedFindings = !showResolvedFindings },
                )
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }

    // ── 弹出的面板 ──

    composing?.let { (sel, kind) ->
        ComposeSheet(sel, kind, onSave = { body -> vm.annotate(sel.toAnchor(), kind, body); composing = null; selection = null }, onDismiss = { composing = null })
    }
    detailId?.let { id ->
        // 同步刷新本机数据时列表可能短暂为空：找不到就先不画，不要关掉（只在用户关掉时清空）
        val item = state.annotations.firstOrNull { it.value.id.toString() == id }
        if (item != null) {
            AnnotationSheet(item, state.people, vm.me, state.zone.let { z -> { t: java.time.Instant -> TIME.format(t.atZone(z)) } }, vm, onDismiss = { detailId = null })
        }
    }
    if (versionSheet) {
        VersionSheet(state.versions, version, state.people, onPick = { vm.selectVersion(it); versionSheet = false }, onCompare = { a, b -> vm.compare(a, b); versionSheet = false },
            onDismiss = { versionSheet = false })
    }
    diff?.let { d -> DiffSheet(d, onDismiss = vm::closeDiff) }
    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
                Text(doc?.title ?: "", style = type.pageTitle.copy(color = colors.ink))
                TextAction("传新版本", { picker.launch(REVIEW_MIME_TYPES); menu = false }, color = colors.ink)
                TextAction("改标题", { renaming = true; menu = false }, color = colors.ink)
                TextAction("删除这份审稿", { removing = true; menu = false }, color = colors.accent)
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }
    if (askingAi && version != null) {
        ConfirmDialog(
            "请 AI 看第 ${version.version} 版？",
            "会把这一版的文字（不含图片）发给 AI 服务商看一遍，只这一次。AI 只指出值得注意的地方，每条都附原文；要不要改、怎么改，由你们决定。",
            "发给 AI",
            onConfirm = {
                askingAi = false
                vm.askAi()?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            },
            onDismiss = { askingAi = false },
        )
    }
    if (renaming && doc != null) RenameSheet(doc.title, onSave = { vm.rename(it); renaming = false }, onDismiss = { renaming = false })
    if (removing && doc != null) {
        ConfirmDialog("删除这份审稿？", "「${doc.title}」连同所有版本和批注会进回收站，可以恢复。", "删除",
            onConfirm = { vm.deleteDocument(); removing = false; onBack() }, onDismiss = { removing = false })
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, dot: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Box(Modifier.heightIn(min = 46.dp).clickable(role = Role.Tab, onClick = onClick).semantics { contentDescription = if (selected) "$text，已选中" else text },
        contentAlignment = Alignment.Center) {
        Text(text, style = type.tab.copy(color = if (selected) colors.ink else colors.muted))
        if (dot) Box(Modifier.align(Alignment.TopCenter).offset(y = 4.dp).size(4.dp).clip(CircleShape).background(colors.accent))
    }
}

/**
 * 一页预览：图片 + 批注的圈和序号 + 选中的高亮。双指缩放、放大后拖动；点一下选文字块，长按拖动圈区域。
 * 坐标一律按页面比例（0–1）。
 */
@Composable
private fun PageView(
    page: ReviewPage,
    imageUrl: String,
    format: ReviewFormat,
    annotations: List<AnnotationItem>,
    selection: Selection?,
    highlighted: String?,
    onSelect: (Selection?) -> Unit,
    onOpenAnnotation: (UUID) -> Unit,
) {
    val colors = QichiTheme.colors
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val transform = rememberTransformableState { zoom, offset, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        pan = if (scale == 1f) Offset.Zero else pan + offset
    }

    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).transformable(transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
        val ratio = (page.width / page.height).toFloat()
        val w = min(maxWidth.value, maxHeight.value * ratio)
        val h = w / ratio
        Box(
            Modifier.size(w.dp, h.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)
                .shadow(6.dp, RoundedCornerShape(4.dp))
                .background(Color.White)
                .pointerInput(page.page, format) {
                    detectTapGestures(
                        onDoubleTap = { if (scale > 1f) { scale = 1f; pan = Offset.Zero } else scale = 2.5f },
                        onTap = { pos -> onSelect(hitTest(page, format, pos.x / size.width, pos.y / size.height)) },
                    )
                }
                .pointerInput(page.page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val a = dragStart
                            val b = dragEnd
                            if (a != null && b != null) onSelect(regionSelection(page, a, b, size.width.toFloat(), size.height.toFloat()))
                            dragStart = null
                            dragEnd = null
                        },
                        onDragCancel = { dragStart = null; dragEnd = null },
                    )
                }
                .semantics { contentDescription = "第 ${page.page} 页预览，${annotations.size} 条批注" },
        ) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            val accent = colors.accent
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                fun rectOf(r: NormRect) = Offset(r.x.toFloat() * size.width, r.y.toFloat() * size.height) to Size(r.w.toFloat() * size.width, r.h.toFloat() * size.height)
                annotations.forEach { item ->
                    val r = markerRect(item.value.anchor, page) ?: return@forEach
                    val (o, s) = rectOf(r)
                    val pad = 6f
                    val strong = item.value.status == AnnotationStatus.Open
                    val focused = highlighted == item.value.id.toString()
                    drawOval(accent.copy(alpha = if (strong) 0.08f else 0.03f), Offset(o.x - pad, o.y - pad), Size(s.width + 2 * pad, s.height + 2 * pad))
                    drawOval(accent.copy(alpha = if (strong) 1f else 0.35f), Offset(o.x - pad, o.y - pad), Size(s.width + 2 * pad, s.height + 2 * pad),
                        style = Stroke(width = if (focused) 3.5f else 2f))
                }
                selection?.rect?.let { r ->
                    val (o, s) = rectOf(r)
                    drawRoundRect(accent.copy(alpha = 0.14f), o, s, CornerRadius(4f, 4f))
                    drawRoundRect(accent, o, s, CornerRadius(4f, 4f), style = Stroke(width = 2f))
                }
                val a = dragStart
                val b = dragEnd
                if (a != null && b != null) {
                    val o = Offset(min(a.x, b.x), min(a.y, b.y))
                    drawRoundRect(accent.copy(alpha = 0.12f), o, Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), CornerRadius(4f, 4f))
                }
                if (selection?.kind == AnchorKind.Slide) drawRect(accent, style = Stroke(width = 4f))
            }
            // 序号：钉在圈的右上角；整页的批注钉在页面右上角
            annotations.forEach { item ->
                val r = markerRect(item.value.anchor, page)
                val x = ((r?.let { it.x + it.w } ?: 1.0).coerceIn(0.03, 0.97) * w).dp - 10.dp
                val y = ((r?.y ?: 0.0).coerceIn(0.0, 0.95) * h).dp - 10.dp
                Box(
                    Modifier.offset { with(density) { IntOffset(x.roundToPx(), y.roundToPx()) } }.size(20.dp).clip(CircleShape)
                        .background(if (item.value.status == AnnotationStatus.Open) accent else accent.copy(alpha = 0.4f))
                        .clickable(role = Role.Button, onClickLabel = "看批注 ${item.number}") { onOpenAnnotation(item.value.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${item.number}", style = QichiTheme.typography.numeral.copy(fontSize = 13.tsp, color = colors.onPerson))
                }
            }
        }
    }
}

/** 批注画在哪：有矩形用矩形；只有文字块 id 时用那块的位置；整页的没有圈。 */
private fun markerRect(anchor: AnnotationAnchor, page: ReviewPage): NormRect? =
    anchor.rect ?: anchor.ref?.let { ref -> page.blocks.firstOrNull { it.id == ref }?.rect }

/** 点在哪：文字块（最小的那块）→ 图片 → 幻灯片整页；什么都没点中就取消选中。 */
internal fun hitTest(page: ReviewPage, format: ReviewFormat, x: Float, y: Float): Selection? {
    val slack = 0.006
    fun NormRect.contains(px: Float, py: Float) = px >= this.x - slack && px <= this.x + this.w + slack && py >= this.y - slack && py <= this.y + this.h + slack
    page.blocks.filter { it.rect.contains(x, y) }.minByOrNull { it.rect.w * it.rect.h }?.let { b ->
        return Selection(page.page, b.kind, b.rect, b.id, b.text)
    }
    page.images.filter { it.contains(x, y) }.minByOrNull { it.w * it.h }?.let { r ->
        return Selection(page.page, AnchorKind.Image, r, null, null)
    }
    if (format == ReviewFormat.Slides) return Selection(page.page, AnchorKind.Slide, null, null, page.blocks.firstOrNull()?.text)
    return null
}

/** 长按拖出的区域；摘录里放区域里的文字（在新版本里找位置用）。太小的当没圈。 */
internal fun regionSelection(page: ReviewPage, a: Offset, b: Offset, width: Float, height: Float): Selection? {
    val x0 = (min(a.x, b.x) / width).coerceIn(0f, 1f)
    val y0 = (min(a.y, b.y) / height).coerceIn(0f, 1f)
    val x1 = (max(a.x, b.x) / width).coerceIn(0f, 1f)
    val y1 = (max(a.y, b.y) / height).coerceIn(0f, 1f)
    if (x1 - x0 < 0.02f || y1 - y0 < 0.01f) return null
    val rect = NormRect(x0.toDouble(), y0.toDouble(), (x1 - x0).toDouble(), (y1 - y0).toDouble())
    val inside = page.blocks.filter { bl -> bl.rect.x < rect.x + rect.w && bl.rect.x + bl.rect.w > rect.x && bl.rect.y < rect.y + rect.h && bl.rect.y + bl.rect.h > rect.y }
    return Selection(page.page, AnchorKind.Region, rect, null, inside.joinToString(" ") { it.text }.ifBlank { null })
}

@Composable
private fun AnnotationRow(item: AnnotationItem, people: People, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val a = item.value
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.s), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("${item.number}", style = type.numeral.copy(fontSize = 21.tsp, color = if (a.status == AnnotationStatus.Open) colors.accent else colors.faint),
            modifier = Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(a.body, style = type.bodyLarge.copy(color = if (a.status == AnnotationStatus.Open) colors.ink else colors.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                PersonMark(people.markChar(a.authorId), people.person(a.authorId), size = 16.dp)
                Text(statusLine(item), style = type.caption.copy(color = colors.muted), maxLines = 1)
            }
        }
    }
}

private fun statusLine(item: AnnotationItem): String {
    val a = item.value
    return buildList {
        if (a.kind == AnnotationKind.Proposal) add("修改提议")
        add(
            when (a.status) {
                AnnotationStatus.Accepted -> if (a.kind == AnnotationKind.Proposal) "已接受" else "已解决"
                AnnotationStatus.Archived -> "已归档"
                AnnotationStatus.Open -> if (item.replies.isNotEmpty()) "讨论中 · ${item.replies.size}" else "待处理"
            },
        )
        item.carriedFrom?.let { add("来自 v$it") }
        if (a.anchorLost) add("位置要再看一眼")
        if (item.local.isPending) add("等待发送")
    }.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeSheet(sel: Selection, kind: AnnotationKind, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var body by rememberSaveable { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(if (kind == AnnotationKind.Proposal) "提修改" else "批注", style = type.pageTitle.copy(color = colors.ink))
            sel.quote?.let { Text("“${it.take(200)}”", style = type.caption.copy(color = colors.muted)) }
            Text("第 ${sel.page} 页", style = type.numeral.copy(fontSize = 15.tsp, color = colors.faint))
            QichiTextField(
                body, { body = it.take(Limits.ANNOTATION_BODY_LENGTH.last) },
                label = if (kind == AnnotationKind.Proposal) "建议改成什么" else "想说什么",
                placeholder = if (kind == AnnotationKind.Proposal) "比如：单价按合同改成 1100" else "比如：这里的数字和合同对不上",
                singleLine = false,
            )
            PrimaryButton("保存", { onSave(body) }, enabled = body.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationSheet(item: AnnotationItem, people: People, me: UUID?, time: (java.time.Instant) -> String, vm: ReviewViewModel, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val a = item.value
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var reply by rememberSaveable(a.id) { mutableStateOf("") }
        var edited by rememberSaveable(a.id) { mutableStateOf(a.body) }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("${item.number}", style = type.numeral.copy(fontSize = 24.tsp, color = colors.accent))
                Text(if (a.kind == AnnotationKind.Proposal) "修改提议" else "批注", style = type.pageTitle.copy(color = colors.ink), modifier = Modifier.weight(1f))
                Text("第 ${a.anchor.page} 页", style = type.numeral.copy(fontSize = 15.tsp, color = colors.faint))
            }
            a.anchor.quote?.let { Text("“${it.take(300)}”", style = type.caption.copy(color = colors.muted)) }
            if (a.anchorLost) {
                Text("从 v${item.carriedFrom ?: "?"} 带过来时没找到原来的位置，钉在了原来的页码上，请再看一眼。", style = type.caption.copy(color = colors.accent))
            } else if (item.carriedFrom != null) {
                Text("从 v${item.carriedFrom} 带过来的，前面的讨论也在下面。", style = type.caption.copy(color = colors.faint))
            }
            if (editing) {
                QichiTextField(edited, { edited = it.take(Limits.ANNOTATION_BODY_LENGTH.last) }, label = "改批注", singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    TextAction("保存", { vm.edit(a, edited); editing = false }, enabled = edited.isNotBlank())
                    TextAction("取消", { edited = a.body; editing = false }, color = colors.muted)
                }
            } else {
                Text(a.body, style = type.bodyLarge.copy(color = colors.ink))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PersonMark(people.markChar(a.authorId), people.person(a.authorId), size = 16.dp)
                Text("${people.name(a.authorId)} · ${time(a.createdAt)}", style = type.caption.copy(color = colors.muted))
            }
            a.resolvedBy?.let { by ->
                val what = when (a.status) { AnnotationStatus.Accepted -> if (a.kind == AnnotationKind.Proposal) "接受了" else "标为已解决"; AnnotationStatus.Archived -> "归档了"; else -> "" }
                if (what.isNotEmpty()) Text("${people.name(by)} ${a.resolvedAt?.let(time) ?: ""} $what", style = type.caption.copy(color = colors.faint))
            }
            // 状态：接受 / 已解决、归档、重新打开（两个人都可以）
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (a.status == AnnotationStatus.Open) {
                    TextAction(if (a.kind == AnnotationKind.Proposal) "接受" else "已解决", { vm.setStatus(a, AnnotationStatus.Accepted) })
                    TextAction("归档", { vm.setStatus(a, AnnotationStatus.Archived) }, color = colors.muted)
                } else {
                    TextAction("重新打开", { vm.setStatus(a, AnnotationStatus.Open) }, color = colors.muted)
                }
                if (a.authorId == me && !editing) {
                    TextAction("改", { editing = true }, color = colors.muted)
                    TextAction("删除", { deleting = true }, color = colors.muted)
                }
            }
            SectionLabel("讨论", modifier = Modifier.padding(top = Spacing.s))
            if (item.replies.isEmpty()) Text("还没有人回复。", style = type.caption.copy(color = colors.faint))
            item.replies.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(vertical = 4.dp)) {
                    PersonMark(people.markChar(r.authorId), people.person(r.authorId), size = 16.dp, modifier = Modifier.padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.body, style = type.body.copy(color = colors.ink))
                        Text(time(r.createdAt), style = type.caption.copy(color = colors.faint))
                    }
                }
            }
            QichiTextField(reply, { reply = it.take(Limits.ANNOTATION_REPLY_LENGTH.last) }, label = "回复", placeholder = "说点什么", singleLine = false)
            PrimaryButton("回复", { vm.reply(a, reply); reply = "" }, enabled = reply.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.s))
        }
    }
    if (deleting) {
        ConfirmDialog("删除这条批注？", "会进回收站，可以恢复。", "删除", onConfirm = { vm.delete(a); deleting = false; onDismiss() }, onDismiss = { deleting = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionSheet(
    versions: List<ReviewVersion>,
    current: ReviewVersion?,
    people: People,
    onPick: (Int) -> Unit,
    onCompare: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var compareFrom by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
            Text("版本", style = type.pageTitle.copy(color = colors.ink))
            Text(if (compareFrom == null) "每个版本都不会被改动。点一个看它。" else "再点一个版本，和 v$compareFrom 对比。", style = type.caption.copy(color = colors.muted))
            versions.asReversed().forEach { v ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        val from = compareFrom
                        if (from != null) { if (from != v.version) onCompare(min(from, v.version), max(from, v.version)) } else onPick(v.version)
                    }.padding(vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Text("v${v.version}", style = type.numeral.copy(fontSize = 22.tsp, color = if (v.id == current?.id) colors.accent else colors.ink), modifier = Modifier.width(44.dp))
                    PersonMark(people.markChar(v.uploadedBy), people.person(v.uploadedBy), size = 16.dp)
                    Column(Modifier.weight(1f)) {
                        Text(v.fileName, style = type.body.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            relativeDay(v.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate(), java.time.LocalDate.now()).first + " · " + when (v.previewStatus) {
                                PreviewStatus.Pending -> "正在生成预览"
                                PreviewStatus.Failed -> "预览没能生成"
                                PreviewStatus.Ready -> "${v.pageCount ?: 0} 页"
                            },
                            style = type.caption.copy(color = colors.muted),
                        )
                    }
                }
            }
            if (versions.count { it.previewStatus == PreviewStatus.Ready } >= 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = Spacing.s)) {
                    ChoicePill(if (compareFrom == null) "选两个版本对比" else "不对比了", compareFrom != null, {
                        compareFrom = if (compareFrom == null) current?.version else null
                    })
                }
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffSheet(d: DiffState, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
            Text("v${d.from} → v${d.to}", style = type.pageTitle.copy(color = colors.ink))
            when {
                d.error != null -> Text(d.error, style = type.caption.copy(color = colors.muted))
                d.diff == null -> Text("正在对比……", style = type.caption.copy(color = colors.muted))
                else -> {
                    val lines = d.diff.lines
                    val added = lines.count { it.kind == DiffKind.Added }
                    val removed = lines.count { it.kind == DiffKind.Removed }
                    Text("新加 $added 处，删去 $removed 处（按文字段落对比，改了字的段落算删去一段、新加一段）", style = type.caption.copy(color = colors.muted),
                        modifier = Modifier.padding(bottom = Spacing.s))
                    DiffView(lines.map { l ->
                        val page = l.newPage ?: l.oldPage
                        Diff.Line(
                            when (l.kind) { DiffKind.Same -> Diff.Kind.Same; DiffKind.Added -> Diff.Kind.Added; DiffKind.Removed -> Diff.Kind.Removed },
                            if (page != null) "p.$page  ${l.text}" else l.text, l.oldPage, l.newPage,
                        )
                    })
                }
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }
}

/** 「p. 2 ¶ 3」：第几页第几块。 */
private fun evidenceLabel(e: FindingEvidence): String = "p. ${e.page} ¶ ${e.ref.substringAfterLast("-b", "?")}"

/** AI 页：每次都要点一下（并确认）才发给 AI；发现带原文证据，只能忽略或转为批注，AI 不替人定稿。 */
@Composable
private fun AiPanel(
    state: ReviewState,
    onAsk: () -> Unit,
    onEvidence: (FindingEvidence) -> Unit,
    onDismiss: (AiFinding) -> Unit,
    onConvert: (AiFinding) -> Unit,
    showResolved: Boolean,
    onToggleResolved: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val ai = state.ai
    Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        when {
            ai.pending != null -> Text("AI 正在看这一版……一般一两分钟。可以先做别的，看完会提醒你。", style = type.caption.copy(color = colors.muted))
            else -> {
                Text(
                    "请 AI 帮着找找前后矛盾、数字对不上、说法含糊、漏掉的条款。每次都要你点一下才会发出去。",
                    style = type.caption.copy(color = colors.muted),
                )
                ai.failed?.let { Text(it, style = type.caption.copy(color = colors.accent)) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    TextAction(if (state.findings.isEmpty()) "请 AI 看这一版" else "请 AI 再看一遍", onAsk,
                        enabled = state.version?.previewStatus == PreviewStatus.Ready, color = colors.personB)
                    if (!ai.enabled) Text("（AI 还没有开启）", style = type.caption.copy(color = colors.faint))
                    else if (!ai.online) Text("（需要联网）", style = type.caption.copy(color = colors.faint))
                }
            }
        }
        state.newFindings.forEach { item -> FindingCard(item, onEvidence, onDismiss, onConvert) }
        val resolved = state.findings.filter { it.value.status != FindingStatus.New }
        if (resolved.isNotEmpty()) {
            TextAction(if (showResolved) "收起处理过的" else "处理过的 ${resolved.size} 条", onToggleResolved, color = colors.muted)
            if (showResolved) resolved.forEach { item ->
                val f = item.value
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI", style = type.numeral.copy(fontSize = 15.tsp, color = colors.faint))
                    Text(f.title, style = type.body.copy(color = colors.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.status == FindingStatus.Converted) "已转为批注" else "已忽略", style = type.caption.copy(color = colors.faint))
                }
            }
        }
    }
}

@Composable
private fun FindingCard(item: FindingItem, onEvidence: (FindingEvidence) -> Unit, onDismiss: (AiFinding) -> Unit, onConvert: (AiFinding) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val f = item.value
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.surface).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI", style = type.numeral.copy(fontSize = 19.tsp, color = colors.personB))
            Text(f.title, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
        }
        if (f.body.isNotBlank()) Text(f.body, style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = 4.dp))
        // 原文证据：两列排开，点一下跳到那一页并圈出那一段
        f.evidence.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { e ->
                    Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "看原文") { onEvidence(e) }
                        .semantics(mergeDescendants = true) { contentDescription = "原文：${e.quote}，第 ${e.page} 页" }) {
                        Text("“${e.quote}”", style = type.body.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(evidenceLabel(e), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val notes = buildList {
            item.carriedFrom?.let { add("v$it 里就有") }
            f.goneInVersion?.let { add("v$it 里找不到这段原文了，可能已经改好") }
            if (item.local.isPending) add("等待发送")
        }
        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction("忽略", { onDismiss(f) }, color = colors.muted)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, colors.line2, RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button) { onConvert(f) }.padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("转为批注", style = type.body.copy(color = colors.ink))
            }
        }
    }
}
