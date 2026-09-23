package app.qichi.feature.reading

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
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
import app.qichi.core.ui.DateChoice
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Book
import app.qichi.shared.rules.Limits
import java.time.LocalDate
import java.util.UUID

private fun mb(bytes: Long) = "${(bytes / (1024 * 1024)).coerceAtLeast(if (bytes > 0) 1 else 0)} MB"

/** 共同书架：各自的进度、是否已下载、共读计划；右上角加 EPUB，「离线」设缓存上限。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShelfScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    vm: ShelfViewModel = hiltViewModel<ShelfViewModel, ShelfViewModel.Factory>(key = "shelf-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var menuFor by remember { mutableStateOf<ShelfBook?>(null) }
    var planning by remember { mutableStateOf<Book?>(null) }
    var removing by remember { mutableStateOf<Book?>(null) }
    var cacheSheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::add) }

    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("阅读", onBack) {
            TextAction("离线", { cacheSheet = true }, color = colors.muted)
            IconAction(QichiIcons.Plus, "加一本书", { picker.launch(arrayOf("application/epub+zip")) }, enabled = adding == null)
        }
        adding?.let { Text("正在上传 ${(it * 100).toInt()}%", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(horizontal = Spacing.page)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            if (state.loaded && state.books.isEmpty()) {
                Text("书架还是空的。右上角可以放一本 EPUB 上来，两个人一起读。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
            }
            state.books.forEach { item -> BookRow(item, state.people, state.today, onClick = { onOpen(item.book.value.id) }, onLongClick = { menuFor = item }) }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    menuFor?.let { item ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s)) {
                Text(item.book.value.title, style = type.pageTitle.copy(color = colors.ink))
                TextAction("共读计划", { planning = item.book.value; menuFor = null }, color = colors.ink)
                if (item.cached) TextAction("删掉这台手机上的下载（${mb(item.book.value.sizeBytes)}）", { vm.removeDownload(item.book.value); menuFor = null }, color = colors.ink)
                TextAction("从书架拿下来", { removing = item.book.value; menuFor = null }, color = colors.accent)
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }
    planning?.let { book ->
        ModalBottomSheet(onDismissRequest = { planning = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
            var target by remember { mutableStateOf(book.planTargetDate) }
            var note by rememberSaveable { mutableStateOf(book.planNote.orEmpty()) }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Text("共读计划", style = type.pageTitle.copy(color = colors.ink))
                DateChoice("一起读完的日子", target, state.today) { target = it }
                QichiTextField(note, { note = it.take(Limits.BOOK_PLAN_NOTE_MAX) }, label = "怎么读（可以不写）", placeholder = "比如：每周读两章，周日聊一聊", singleLine = false)
                PrimaryButton("保存", { vm.updatePlan(book, target, note); planning = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    removing?.let { book ->
        ConfirmDialog("从书架拿下来？", "《${book.title}》会进回收站，可以恢复。", "拿下来", onConfirm = { vm.delete(book); removing = null }, onDismiss = { removing = null })
    }
    if (cacheSheet) {
        ModalBottomSheet(onDismissRequest = { cacheSheet = false }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.page, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text("离线缓存", style = type.pageTitle.copy(color = colors.ink))
                Text("读过的书会留在这台手机上，离线也能读。超过上限时，最久没打开的书先删掉（正在读的不删）。现在用了 ${mb(vm.cacheUsed())}。",
                    style = type.caption.copy(color = colors.muted))
                SectionLabel("上限")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(200L, 500L, 1000L, 2000L).forEach { size ->
                        val bytes = size * 1024 * 1024
                        ChoicePill(if (size >= 1000) "${size / 1000} GB" else "$size MB", state.cacheLimit == bytes, { vm.setCacheLimit(bytes) })
                    }
                }
                Spacer(Modifier.height(Spacing.l))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(item: ShelfBook, people: People, today: LocalDate, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val book = item.book.value
    Column(
        Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClickLabel = "打开", onLongClickLabel = "更多", onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.s),
    ) {
        Text(book.title, style = type.feeling.copy(fontSize = 21.tsp, lineHeight = 29.tsp, color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        book.author?.let { Text(it, style = type.caption.copy(color = colors.muted), maxLines = 1) }
        Row(Modifier.padding(top = Spacing.xxs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOfNotNull(item.mine, item.partner).forEach { p ->
                PersonMark(people.markChar(p.userId), people.person(p.userId), size = 16.dp)
                Text("${(p.progress * 100).toInt()}%", style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted))
            }
            val meta = buildList {
                if (item.notes > 0) add("${item.notes} 条笔记")
                add(if (item.cached) "已下载" else mb(book.sizeBytes))
            }
            Text(meta.joinToString(" · "), style = type.caption.copy(color = colors.faint), modifier = Modifier.weight(1f))
        }
        book.planTargetDate?.let { d ->
            Text("计划 ${relativeDay(d, today).first} 读完" + (book.planNote?.let { " · $it" } ?: ""), style = type.caption.copy(color = if (d.isBefore(today)) colors.accent else colors.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
