package app.qichi.feature.chat

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.Local
import app.qichi.core.ui.chatDay
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.model.MessageKind
import app.qichi.shared.rules.MessageRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

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

    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val jumping by viewModel.jumping.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var retracting by remember { mutableStateOf<Message?>(null) }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var attaching by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<FileMeta?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.attach(it, asImage = true) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attach(it, asImage = false) }
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var unseen by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<UUID?>(null) }
    var menuFor by remember { mutableStateOf<Local<Message>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.ScrollTo -> {
                    // 刚从服务端补下来的历史，列表可能还没刷新到：等它包含这个位置
                    withTimeoutOrNull(5_000) { snapshotFlow { items.itemCount }.first { it > event.index } }
                    fun locate(): Int? = (0 until items.itemCount)
                        .sortedBy { abs(it - event.index) }
                        .take(80)
                        .firstOrNull { items.peek(it)?.value?.id == event.id }
                    // 先滚到大致位置；那一段从数据库读出来、占位换成真实高度后，再按 id 校准一次
                    // 列表最下面可能还有正在上传的附件，排在消息前面
                    val extra = uploads.size
                    listState.scrollToItem((event.index - 2).coerceIn(0, (items.itemCount - 1).coerceAtLeast(0)) + extra)
                    val exact = withTimeoutOrNull(3_000) { snapshotFlow { locate() }.first { it != null } }
                    // 让原消息停在靠下的位置，而不是贴着输入框
                    if (exact != null) listState.scrollToItem((exact - 2).coerceAtLeast(0) + extra)
                    highlighted = event.id
                }
                is ChatEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is ChatEvent.OpenFile -> openWithOtherApp(context, event.file, event.mimeType)
            }
        }
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(1_600)
            highlighted = null
        }
    }
    // 最新一条变了：在底部就跟上去，翻在上面时给个「新消息」提示
    val newest by viewModel.newest.collectAsStateWithLifecycle()
    var lastNewestId by remember { mutableStateOf<UUID?>(null) }
    LaunchedEffect(newest?.id) {
        val current = newest ?: return@LaunchedEffect
        val first = lastNewestId == null
        lastNewestId = current.id
        if (first) return@LaunchedEffect
        val wasAtBottom = listState.firstVisibleItemIndex <= 1
        // 分页列表和这条查询各自刷新，先等列表里真的出现这条再滚，否则会停在它上面一条
        withTimeoutOrNull(2_000) { snapshotFlow { items.peek(0)?.value?.id }.first { it == current.id } }
        when {
            // 自己刚发的：总是滚到底
            current.authorId == people.myUserId || wasAtBottom -> listState.animateScrollToItem(0)
            else -> unseen = true
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ChatHeader(online = state.online, onSearch = viewModel::openSearch)
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
                // 正在上传的附件在最下面（倒序列表的最前面），最新的最靠下
                items(uploads.asReversed(), key = { "upload-${it.id}" }) { upload ->
                    UploadItem(
                        upload, maxWidth = maxBubble,
                        onRetry = { viewModel.retryUpload(upload.id) }, onCancel = { viewModel.cancelUpload(upload.id) },
                    )
                }
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
                        maxBubble = maxBubble, highlighted = local.value.id == highlighted,
                        onRetry = viewModel::retry, onAbandon = viewModel::abandon,
                        onLongPress = { menuFor = local }, onQuoteClick = viewModel::jumpTo,
                        attachments = AttachmentActions(
                            urls = viewModel.urls,
                            downloads = downloads,
                            onOpenImage = { viewing = it },
                            onOpenFile = viewModel::openFile,
                        ),
                    )
                }
            }
            if (jumping) {
                Text(
                    "正在找原消息…",
                    style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, letterSpacing = 0.12.em, color = colors.muted),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.paper)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
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
        replyTo?.let { ReplyStrip(it, people, onCancel = viewModel::cancelReply) }
        InputBar(
            draft = draft,
            online = state.online,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onAttach = { if (state.online) attaching = true else viewModel.offlineAttachHint() },
        )
    }

    if (attaching) {
        AttachSheet(
            onDismiss = { attaching = false },
            onImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFile = { pickFile.launch(arrayOf("*/*")) },
        )
    }
    viewing?.let { file -> ImageViewer(file, viewModel.urls, onDismiss = { viewing = null }) }

    menuFor?.let { target ->
        MessageActions(
            local = target,
            people = people,
            onDismiss = { menuFor = null },
            onReply = { viewModel.startReply(target.value) },
            onCopy = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("消息", target.value.body))) }
            },
            onRetract = { retracting = target.value },
            onDelete = { viewModel.delete(target.value) },
        )
    }
    retracting?.let { message ->
        ConfirmDialog(
            title = "撤回这条消息？",
            text = "撤回后对方会看到「${people.name(people.myUserId)}撤回了一条消息」，内容不能恢复。",
            confirmLabel = "撤回",
            onConfirm = { viewModel.retract(message) },
            onDismiss = { retracting = null },
        )
    }
    if (search.open) {
        BackHandler { viewModel.closeSearch() }
        ChatSearch(
            state = search,
            people = people,
            onQuery = viewModel::onSearchQuery,
            onClose = viewModel::closeSearch,
            onLoadMore = viewModel::loadMoreResults,
            onOpen = viewModel::openResult,
        )
    }
}

@Composable
private fun ChatHeader(online: Boolean, onSearch: () -> Unit) {
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
        Box(Modifier.weight(1f))
        IconAction(QichiIcons.Search, contentDescription = "搜索", onClick = onSearch)
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
    highlighted: Boolean,
    onRetry: (Message) -> Unit,
    onAbandon: (Message) -> Unit,
    onLongPress: () -> Unit,
    onQuoteClick: (UUID) -> Unit,
    attachments: AttachmentActions,
) {
    val m = local.value
    val colors = QichiTheme.colors
    // 跳转过来的原消息：底色闪一下再淡去
    val flash by animateColorAsState(
        targetValue = if (highlighted) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = if (QichiTheme.reduceMotion) snap() else tween(if (highlighted) 200 else 900),
        label = "highlight",
    )
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
        Column(Modifier.background(flash, RoundedCornerShape(8.dp))) {
            MessageBody(local, mine, groupedWithNewer, people, zone, maxBubble, onRetry, onAbandon, onLongPress, onQuoteClick, attachments)
        }
    }
}

@Composable
private fun MessageBody(
    local: Local<Message>,
    mine: Boolean,
    groupedWithNewer: Boolean,
    people: People,
    zone: ZoneId,
    maxBubble: Dp,
    onRetry: (Message) -> Unit,
    onAbandon: (Message) -> Unit,
    onLongPress: () -> Unit,
    onQuoteClick: (UUID) -> Unit,
    attachments: AttachmentActions,
) {
    val m = local.value
    when {
        m.retractedAt != null -> Notice(if (m.retractedBy == people.myUserId) "你撤回了一条消息" else "${people.name(m.retractedBy)}撤回了一条消息")
        m.kind == MessageKind.System -> Notice(m.body)
        else -> {
            val file = m.file
            when {
                file != null && m.kind == MessageKind.Image -> AttachmentRow(local, mine) { shape, _ ->
                    ImageBubble(file, shape, attachments.urls, onOpen = { attachments.onOpenImage(file) }, onLongPress = onLongPress)
                }
                file != null -> AttachmentRow(local, mine) { shape, background ->
                    FileBubble(
                        file, shape, background, maxBubble, downloading = attachments.downloads[file.id],
                        onOpen = { attachments.onOpenFile(file) }, onLongPress = onLongPress,
                    )
                }
                else -> TextBubble(local, mine, people, maxBubble, onLongPress = onLongPress, onQuoteClick = onQuoteClick)
            }
            when {
                local.isFailed -> FailedActions(onRetry = { onRetry(m) }, onAbandon = { onAbandon(m) })
                !local.isPending && !groupedWithNewer -> TimeLabel(m, mine, zone)
            }
        }
    }
}

/** 附件消息需要的东西：地址、下载进度、打开图片 / 文件。 */
private class AttachmentActions(
    val urls: FileUrls,
    val downloads: Map<UUID, Float>,
    val onOpenImage: (FileMeta) -> Unit,
    val onOpenFile: (FileMeta) -> Unit,
)

/** 图片、文件气泡的外层：左右对齐、待发送的小时钟，与文字气泡一致。 */
@Composable
private fun AttachmentRow(local: Local<Message>, mine: Boolean, content: @Composable (shape: Shape, background: Modifier) -> Unit) {
    val colors = QichiTheme.colors
    val shape = bubbleShape(mine)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (local.isPending) PendingClock()
        content(shape, Modifier.background(if (mine) colors.personA.copy(alpha = 0.13f) else colors.surface))
    }
}

private fun bubbleShape(mine: Boolean): Shape =
    if (mine) RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp) else RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)

@Composable
private fun PendingClock() {
    Icon(
        QichiIcons.Clock,
        contentDescription = "待发送",
        tint = QichiTheme.colors.muted,
        modifier = Modifier
            .padding(end = 6.dp, bottom = 8.dp)
            .size(14.dp),
    )
}

private fun sameGroup(older: Message, newer: Message): Boolean =
    older.authorId == newer.authorId && older.retractedAt == null && newer.retractedAt == null &&
        Duration.between(older.createdAt, newer.createdAt) < GROUP_WINDOW

@Composable
private fun TextBubble(
    local: Local<Message>,
    mine: Boolean,
    people: People,
    maxBubble: Dp,
    onLongPress: () -> Unit,
    onQuoteClick: (UUID) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val m = local.value
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = bubbleShape(mine)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (local.isPending) PendingClock()
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
                .combinedClickable(
                    onClick = {},
                    onLongClickLabel = "更多操作",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (m.replyToId != null || m.replyExcerpt != null) ReplyQuote(m, people, onClick = m.replyToId?.let { id -> { onQuoteClick(id) } })
            Text(m.body, style = type.body.copy(color = colors.ink))
        }
    }
}

@Composable
private fun ReplyQuote(m: Message, people: People, onClick: (() -> Unit)?) {
    val colors = QichiTheme.colors
    val line = colors.line2
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "跳到原消息", onClick = onClick) else Modifier)
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
        textAlign = if (mine) TextAlign.End else TextAlign.Start,
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
        textAlign = TextAlign.Center,
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
        textAlign = TextAlign.Center,
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

/** 输入框上方：正在回复谁的哪句话，可取消。 */
@Composable
private fun ReplyStrip(message: Message, people: People, onCancel: () -> Unit) {
    val colors = QichiTheme.colors
    val excerpt = MessageRules.replyExcerpt(message.kind, message.body, message.file?.fileName, message.retractedAt != null).orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PersonMark(people.markChar(message.authorId), people.person(message.authorId), size = 14.dp)
        Text(
            "回复 ${people.name(message.authorId)}：$excerpt",
            style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, color = colors.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconAction(QichiIcons.Close, contentDescription = "取消回复", onClick = onCancel, iconSize = 16, tint = colors.muted)
    }
}

/** 长按消息：回复、复制、撤回（自己的）、删除。待发送或发送失败的消息只能复制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(
    local: Local<Message>,
    people: People,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRetract: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val m = local.value
    val synced = !local.isPending && !local.isFailed
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 28.dp)) {
            Text(
                "${people.name(m.authorId)}：${m.body}",
                style = type.caption.copy(color = colors.muted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (synced) ActionRow("回复") { onReply(); onDismiss() }
            if (m.body.isNotEmpty()) ActionRow("复制") { onCopy(); onDismiss() }
            if (synced && m.authorId == people.myUserId && m.retractedAt == null) ActionRow("撤回") { onRetract(); onDismiss() }
            if (synced) ActionRow("删除") { onDelete(); onDismiss() }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
    )
}

@Composable
private fun InputBar(draft: String, online: Boolean, onDraftChange: (String) -> Unit, onSend: () -> Unit, onAttach: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 离线时置灰（仍可点，点了说明为什么不能发）
        IconAction(
            QichiIcons.Plus, contentDescription = if (online) "添加图片或文件" else "添加图片或文件（离线时不可用）",
            onClick = onAttach, iconSize = 24, tint = if (online) colors.ink else colors.faint,
        )
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
