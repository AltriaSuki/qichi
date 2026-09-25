package app.qichi.feature.review

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.REVIEW_MIME_TYPES
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.rules.Limits
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 审稿列表：每份文件的最新版本、还没处理的批注数；右下角「新建审稿」选一个文件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    vm: ReviewListViewModel = hiltViewModel<ReviewListViewModel, ReviewListViewModel.Factory>(key = "reviews-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val opened by vm.opened.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<ReviewDocument?>(null) }
    var renaming by remember { mutableStateOf<ReviewDocument?>(null) }
    var removing by remember { mutableStateOf<ReviewDocument?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::add) }

    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }
    LaunchedEffect(opened) { opened?.let { onOpen(it); vm.openedHandled() } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Review, onBack)
            adding?.let { Text("正在上传 ${(it * 100).toInt()}%", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(horizontal = Spacing.page)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
                if (state.loaded && state.items.isEmpty()) {
                    Text(
                        "还没有要审的文件。点右下角选一个 PDF、Word、Excel 或 PowerPoint，两个人在上面圈出要改的地方、讨论、接受修改。" +
                            "手机上只看服务器生成的预览图，不打开原文件。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m),
                    )
                }
                state.items.forEachIndexed { i, item -> ReviewRow(item, state, first = i == 0, onClick = { onOpen(item.doc.id) }, onLongClick = { menuFor = item.doc }) }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("上传", { picker.launch(REVIEW_MIME_TYPES) }, enabled = adding == null)
    }

    menuFor?.let { doc ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
                Text(doc.title, style = type.pageTitle.copy(color = colors.ink))
                TextAction("改标题", { renaming = doc; menuFor = null }, color = colors.ink)
                TextAction("删除", { removing = doc; menuFor = null }, color = colors.accent)
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }
    renaming?.let { doc -> RenameSheet(doc.title, onSave = { vm.rename(doc, it); renaming = null }, onDismiss = { renaming = null }) }
    removing?.let { doc ->
        ConfirmDialog("删除这份审稿？", "「${doc.title}」连同所有版本和批注会进回收站，可以恢复。", "删除",
            onConfirm = { vm.delete(doc); removing = null }, onDismiss = { removing = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var title by rememberSaveable { mutableStateOf(current) }
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text("改标题", style = type.pageTitle.copy(color = colors.ink))
            QichiTextField(title, { title = it.take(Limits.REVIEW_TITLE_LENGTH.last) }, label = "标题")
            PrimaryButton("保存", { onSave(title) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/** 文件缩略图：白纸（右上折角）、两道字线、一个暮玫瑰的圈。 */
@Composable
private fun DocThumb() {
    val colors = QichiTheme.colors
    val line = Color(0xFFDFE2E0)
    val head = Color(0xFFCDD2D1)
    val ring = colors.accent
    Box(
        Modifier.size(44.dp, 56.dp).lift(colors, RoundedCornerShape(3.dp)).clip(GenericShape { size, _ ->
            moveTo(0f, 0f); lineTo(size.width * .75f, 0f); lineTo(size.width, size.height * .2f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }).background(Color.White).drawBehind {
            val px = 7.dp.toPx()
            drawRoundRect(head, androidx.compose.ui.geometry.Offset(px, 9.dp.toPx()), androidx.compose.ui.geometry.Size((size.width - 2 * px) * .6f, 3.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(line, androidx.compose.ui.geometry.Offset(px, 16.dp.toPx()), androidx.compose.ui.geometry.Size(size.width - 2 * px, 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(line, androidx.compose.ui.geometry.Offset(px, 22.dp.toPx()), androidx.compose.ui.geometry.Size(size.width - 2 * px, 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            rotate(-8f, pivot = androidx.compose.ui.geometry.Offset(24.dp.toPx(), 32.dp.toPx())) {
                drawOval(ring, androidx.compose.ui.geometry.Offset(14.dp.toPx(), 26.dp.toPx()), androidx.compose.ui.geometry.Size(20.dp.toPx(), 12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx()))
            }
        }.clearAndSetSemantics { },
    )
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")

/**
 * 一份审稿（按 New-Review-List）：文件缩略图、标题、版本号、状态贴纸（有没处理的批注写「讨论中」）；
 * 小字是页数和讨论条数、谁传的；右边日期。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewRow(item: ReviewItem, state: ReviewListState, first: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val doc = item.doc
    val latest = item.latest
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.dashedDivider(colors, atTop = true)).heightIn(min = 80.dp)
            .combinedClickable(role = Role.Button, onClickLabel = "打开", onLongClickLabel = "更多", onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        DocThumb()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(doc.title, style = type.headline.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("v${doc.latestVersion}", style = type.numeral.copy(fontSize = 14.tsp, color = colors.muted))
                if (item.open > 0) Sticker("讨论中", rotation = -4f, fontSizeSp = 14f)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val meta = buildList {
                    latest?.let { v ->
                        when (v.previewStatus) {
                            PreviewStatus.Pending -> add("正在生成预览")
                            PreviewStatus.Failed -> add("预览没能生成")
                            PreviewStatus.Ready -> add("${v.pageCount ?: 0} 页")
                        }
                    }
                    add(if (item.open > 0) "${item.open} 条讨论" else "没有待处理的")
                }
                Text(meta.joinToString(" · "), style = type.caption.copy(color = colors.muted), maxLines = 1)
                latest?.let { PersonMark(state.people.markChar(it.uploadedBy), state.people.person(it.uploadedBy), size = 16.dp) }
            }
        }
        latest?.let { Text(it.createdAt.atZone(state.zone).format(MD), style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted)) }
    }
}
