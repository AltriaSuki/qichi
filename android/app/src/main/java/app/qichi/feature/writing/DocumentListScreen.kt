package app.qichi.feature.writing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Document
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

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("共同写作", onBack) {
            IconAction(QichiIcons.Plus, "新文稿", { creating = true })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            if (state.loaded && state.documents.isEmpty()) {
                Text("还没有文稿。一封信、一份清单、一篇两个人一起写的东西，都可以从这里开始。",
                    style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                TextAction("写一篇", { creating = true })
            }
            state.documents.forEach { doc ->
                DocumentRow(doc, doc.value.id in state.unsaved, state.people, state.zone, state.today, onClick = { onOpen(doc.value.id) })
            }
            Spacer(Modifier.height(Spacing.xl))
        }
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

@Composable
private fun DocumentRow(local: Local<Document>, unsaved: Boolean, people: People, zone: ZoneId, today: LocalDate, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val doc = local.value
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开文稿", onClick = onClick).padding(vertical = Spacing.s)) {
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
