package app.qichi.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.chatDay
import app.qichi.shared.api.Message
import app.qichi.shared.model.MessageKind
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 同一个人 5 分钟内连着发的算一组：组内间距小，只在最后一条下面写时间。 */
private val GROUP_WINDOW: Duration = Duration.ofMinutes(5)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** 聊天（按 Chat.dc.html）：倒序列表、待发送的小时钟、发送失败可重试、回到底部与新消息提示。 */
@Composable
fun ChatScreen(
    roomId: UUID,
    viewModel: ChatViewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    val colors = QichiTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val people = state.people

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var unseen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.sent.collect { listState.scrollToItem(0) } }
    // 最新一条变了：在底部就跟上去，翻在上面时给个「新消息」提示
    val newest by viewModel.newest.collectAsStateWithLifecycle()
    var lastNewestId by remember { mutableStateOf<UUID?>(null) }
    LaunchedEffect(newest?.id) {
        val current = newest ?: return@LaunchedEffect
        val first = lastNewestId == null
        lastNewestId = current.id
        if (first) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        } else if (current.authorId != people.myUserId) {
            unseen = true
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ChatHeader(online = state.online)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val maxBubble = (maxWidth - 44.dp) * 0.76f
            val zone = remember { ZoneId.systemDefault() }
            val today = remember { LocalDate.now(zone) }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.value.id.toString() },
                    contentType = items.itemContentType { it.value.kind },
                ) { index ->
                    val local = items[index]
                    if (local == null) {
                        // 还没从数据库读出来的位置：先占一个大概的高度
                        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp))
                        return@items
                    }
                    val older = if (index + 1 < items.itemCount) items.peek(index + 1)?.value else null
                    val newer = if (index > 0) items.peek(index - 1)?.value else null
                    MessageRow(
                        local = local, older = older, newer = newer, people = people, zone = zone, today = today,
                        maxBubble = maxBubble, onRetry = viewModel::retry, onAbandon = viewModel::abandon,
                    )
                }
            }
            if (!atBottom) {
                JumpToBottom(
                    unseen = unseen,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp),
                )
            }
        }
        InputBar(
            draft = draft,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
        )
    }
}

@Composable
private fun ChatHeader(online: Boolean) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 28.dp, top = 28.dp, end = 12.dp, bottom = 14.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "聊天",
            style = type.pageTitle.copy(fontSize = 24.tsp, lineHeight = 32.tsp, letterSpacing = 0.3.em, color = colors.ink),
            modifier = Modifier.semantics { heading() },
        )
        if (!online) Text("离线", style = type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.2.em, color = colors.accent))
    }
}

@Composable
private fun MessageRow(
    local: Local<Message>,
    older: Message?,
    newer: Message?,
    people: People,
    zone: ZoneId,
    today: LocalDate,
    maxBubble: Dp,
    onRetry: (Message) -> Unit,
    onAbandon: (Message) -> Unit,
) {
    val m = local.value
    val day = m.createdAt.atZone(zone).toLocalDate()
    val newDay = older == null || older.createdAt.atZone(zone).toLocalDate() != day
    val groupedWithOlder = !newDay && older != null && sameGroup(older, m)
    val groupedWithNewer = newer != null && sameGroup(m, newer) && newer.createdAt.atZone(zone).toLocalDate() == day
    val mine = m.authorId != null && m.authorId == people.myUserId

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (older == null) 0.dp else if (groupedWithOlder) 6.dp else 16.dp),
    ) {
        if (newDay) {
            DaySeparator(day, today)
            Box(Modifier.size(16.dp))
        }
        when {
            m.retractedAt != null -> Notice(if (m.retractedBy == people.myUserId) "你撤回了一条消息" else "${people.name(m.retractedBy)}撤回了一条消息")
            m.kind == MessageKind.System -> Notice(m.body)
            else -> {
                TextBubble(local, mine, people, maxBubble)
                when {
                    local.isFailed -> FailedActions(onRetry = { onRetry(m) }, onAbandon = { onAbandon(m) })
                    !local.isPending && !groupedWithNewer -> TimeLabel(m, mine, zone)
                }
            }
        }
    }
}

private fun sameGroup(older: Message, newer: Message): Boolean =
    older.authorId == newer.authorId && older.retractedAt == null && newer.retractedAt == null &&
        Duration.between(older.createdAt, newer.createdAt) < GROUP_WINDOW

@Composable
private fun TextBubble(local: Local<Message>, mine: Boolean, people: People, maxBubble: Dp) {
    val m = local.value
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = if (mine) RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp) else RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (local.isPending) {
            Icon(
                QichiIcons.Clock,
                contentDescription = "待发送",
                tint = colors.muted,
                modifier = Modifier
                    .padding(end = 6.dp, bottom = 8.dp)
                    .size(14.dp),
            )
        }
        // 待发送、发送失败：透明底 + 细边
        val unsent = local.isPending || local.isFailed
        Column(
            Modifier
                .widthIn(max = maxBubble)
                .clip(shape)
                .then(
                    when {
                        unsent -> Modifier.border(1.dp, if (local.isFailed) colors.accent else colors.personA, shape)
                        mine -> Modifier.background(colors.personA.copy(alpha = 0.13f))
                        else -> Modifier.background(colors.surface)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (m.replyToId != null || m.replyExcerpt != null) ReplyQuote(m, people)
            Text(m.body, style = type.body.copy(color = colors.ink))
        }
    }
}

@Composable
private fun ReplyQuote(m: Message, people: People) {
    val colors = QichiTheme.colors
    val line = colors.line2
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (m.replyAuthorId != null) PersonMark(people.markChar(m.replyAuthorId), people.person(m.replyAuthorId), size = 14.dp)
        Text(
            m.replyExcerpt ?: "原消息已撤回",
            style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, color = colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimeLabel(m: Message, mine: Boolean, zone: ZoneId) {
    Text(
        TIME.format(m.createdAt.atZone(zone)),
        style = QichiTheme.typography.numeral.copy(fontSize = 15.tsp, color = QichiTheme.colors.muted),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp),
        textAlign = if (mine) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

@Composable
private fun FailedActions(onRetry: () -> Unit, onAbandon: () -> Unit) {
    val colors = QichiTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("发送失败", style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, color = colors.accent))
        TextAction("重试", onClick = onRetry)
        TextAction("不发了", onClick = onAbandon, color = colors.muted)
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, letterSpacing = 0.12.em, color = QichiTheme.colors.muted),
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun DaySeparator(day: LocalDate, today: LocalDate) {
    val (text, numeral) = chatDay(day, today)
    val type = QichiTheme.typography
    val colors = QichiTheme.colors
    Text(
        text,
        style = if (numeral) type.numeral.copy(fontSize = 15.tsp, color = colors.muted) else type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.2.em, color = colors.muted),
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun JumpToBottom(unseen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.paper)
            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (unseen) "有新消息，回到底部" else "回到底部" }
            .padding(horizontal = if (unseen) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (unseen) Text("新消息", style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, letterSpacing = 0.2.em, color = colors.accent))
        Icon(QichiIcons.Down, contentDescription = null, tint = if (unseen) colors.accent else colors.ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InputBar(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 图片与文件在 P3-06 接上
        IconAction(QichiIcons.Plus, contentDescription = "添加图片或文件", onClick = {}, enabled = false, iconSize = 24)
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "消息" },
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("说点什么", style = type.body.copy(color = colors.faint))
                    inner()
                },
            )
        }
        val canSend = draft.isNotBlank()
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (canSend) colors.ink else colors.line2)
                .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(QichiIcons.Send, contentDescription = null, tint = colors.background, modifier = Modifier.size(20.dp))
        }
    }
}
