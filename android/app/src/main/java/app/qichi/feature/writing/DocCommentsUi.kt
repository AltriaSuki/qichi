package app.qichi.feature.writing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.sync.Local
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.Markdown
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.DocComment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一条讨论：开头、回复（从早到晚）、在当前正文里的位置（null = 原文已改动，找不到了）。 */
internal data class CommentThread(val root: Local<DocComment>, val replies: List<Local<DocComment>>, val block: Int?) {
    val resolved: Boolean get() = root.value.resolvedAt != null
}

/** 按当前正文把留言分成讨论并找回位置；还没解决的在前，同一处按时间。 */
internal fun commentThreads(comments: List<Local<DocComment>>, blocks: List<Markdown.Block>): List<CommentThread> {
    val replies = comments.filter { it.value.parentId != null }.groupBy { it.value.parentId }
    return comments.filter { it.value.parentId == null }
        .map { root -> CommentThread(root, replies[root.value.id].orEmpty(), CommentAnchors.locate(blocks, root.value.quote.orEmpty())) }
        .sortedWith(compareBy({ it.resolved }, { it.block ?: Int.MAX_VALUE }, { it.root.value.createdAt }))
}

private val hm = DateTimeFormatter.ofPattern("HH:mm")

private fun whenText(at: Instant): String {
    val zone = ZoneId.systemDefault()
    val t = at.atZone(zone)
    return "${relativeDay(t.toLocalDate(), LocalDate.now(zone)).first} ${t.format(hm)}"
}

/**
 * 留言列表（P9-03）：某一段的讨论，或者全部（[title] 区分）。每条讨论：钉住的原文、开头、回复；
 * 可以回复、标为解决 / 重新打开、删掉自己写的讨论。都可以离线做，联网补发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsSheet(
    title: String,
    threads: List<CommentThread>,
    people: People,
    onReply: (DocComment, String) -> Unit,
    onResolve: (DocComment, Boolean) -> Unit,
    onDelete: (DocComment) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = Spacing.page)) {
            SectionLabel(title)
            if (threads.isEmpty()) {
                Text("还没有留言。在预览里长按一段就能留言。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.m))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.l), modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xl)) {
                items(threads, key = { it.root.value.id }) { thread ->
                    ThreadView(thread, people, onReply, onResolve, onDelete)
                }
            }
        }
    }
}

@Composable
private fun ThreadView(
    thread: CommentThread,
    people: People,
    onReply: (DocComment, String) -> Unit,
    onResolve: (DocComment, Boolean) -> Unit,
    onDelete: (DocComment) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val root = thread.root.value
    var replying by rememberSaveable(root.id) { mutableStateOf(false) }
    var draft by rememberSaveable(root.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().then(if (thread.resolved) Modifier.alpha(0.6f) else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("“${root.quote.orEmpty()}”", style = type.caption.copy(color = colors.faint), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                thread.resolved -> Text("已解决", style = type.caption.copy(color = colors.muted))
                thread.block == null -> Text("原文已改动", style = type.caption.copy(color = colors.accent))
            }
        }
        CommentLine(thread.root, people)
        thread.replies.forEach { r -> Row { Spacer(Modifier.padding(start = 26.dp)); CommentLine(r, people) } }
        if (replying) {
            QichiTextField(draft, { draft = it }, label = "回复", singleLine = false, modifier = Modifier.padding(top = Spacing.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction("取消", { replying = false; draft = "" }, color = colors.muted)
                Spacer(Modifier.padding(start = Spacing.xs))
                PrimaryButton("回复", { onReply(root, draft); draft = ""; replying = false }, enabled = draft.isNotBlank())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                if (root.authorId == people.myUserId) TextAction("删除", { onDelete(root) }, color = colors.muted)
                TextAction(if (thread.resolved) "重新打开" else "解决", { onResolve(root, !thread.resolved) }, color = colors.muted)
                TextAction("回复", { replying = true })
            }
        }
    }
}

@Composable
private fun CommentLine(c: Local<DocComment>, people: People) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val v = c.value
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PersonMark(people.markChar(v.authorId), people.person(v.authorId), size = 20.dp, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                people.name(v.authorId) + " · " + whenText(v.createdAt) + if (c.isPending) " · 等待发送" else "",
                style = type.caption.copy(fontSize = 12.tsp, color = colors.faint),
            )
            Text(v.body, style = type.body.copy(color = colors.ink))
        }
    }
}

/** 写新留言：上面是钉住的原文，下面写想说的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewCommentSheet(quote: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var body by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = Spacing.page).padding(bottom = Spacing.xl)) {
            SectionLabel("给这一段留言")
            Text("“$quote”", style = type.caption.copy(color = colors.muted), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = Spacing.xs))
            QichiTextField(body, { body = it }, label = "留言", placeholder = "比如：这里可以再具体一点", singleLine = false)
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton("留言", { onSubmit(body) }, enabled = body.isNotBlank())
            }
        }
    }
}
