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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.AiMark
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.RefChip
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.ThinkingDots
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Polaroid
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.topBarInset
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.liftFlat
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.FileUrls
import app.qichi.core.sync.Local
import app.qichi.core.ui.chatDay
import app.qichi.core.ui.feelingWord
import app.qichi.core.ui.scrollToItemMotion
import app.qichi.core.ui.sourceKind
import app.qichi.core.ui.sourceLabel
import app.qichi.shared.api.AiAction
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.Mood
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.MessageKind
import app.qichi.shared.rules.MessageRules
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 同一个人 5 分钟内连着发的算一组：组内间距小，只在最后一条下面写时间。 */
private val GROUP_WINDOW: Duration = Duration.ofMinutes(5)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** 聊天（按 Chat.dc.html）：倒序列表、待发送的小时钟、发送失败可重试、回到底部与新消息提示。 */
@Composable
fun ChatScreen(
    roomId: UUID,
    /** 进入后跳到这条消息（通知、档案的来源） */
    jumpTo: UUID? = null,
    /** 长按「存进档案」 */
    onArchive: (Message) -> Unit = {},
    /** 点 AI 回答里的 [n]：打开引用的那条记录（聊天消息在这里直接跳） */
    onOpenSource: (SummarySource) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    val colors = QichiTheme.colors
    val listState = rememberLazyListState()
    val reduceMotion = QichiTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val people = state.people

    // 打开聊天（或从后台回到聊天）：这个房间的聊天通知清掉
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        app.qichi.core.push.PushNotifier.clearChat(appContext, roomId)
    }

    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    // 从通知或档案的来源进来：跳到那条消息（只跳一次）
    var jumped by rememberSaveable(jumpTo) { mutableStateOf(false) }
    LaunchedEffect(jumpTo) {
        if (jumpTo != null && !jumped) {
            jumped = true
            viewModel.jumpTo(jumpTo)
        }
    }
    val jumping by viewModel.jumping.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val pendingAi by viewModel.pendingAi.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val lastRead by viewModel.lastRead.collectAsStateWithLifecycle()
    val newestSeq by viewModel.newestSeq.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    // 「新消息」分隔线：每次回到聊天时记下当时的未读位置，停留期间不跟着移动；进来时没有未读就不显示。
    // （必须写在推进未读位置之前：这里读到的是推进前的值）
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var dividerAfter by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(visible) { if (visible) dividerAfter = null }
    LaunchedEffect(lastRead, newestSeq, dividerAfter == null) {
        val read = lastRead ?: return@LaunchedEffect
        if (dividerAfter == null && newestSeq > 0) dividerAfter = if (newestSeq > read) read else Long.MAX_VALUE
    }
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
    val aiActions by viewModel.aiActions.collectAsStateWithLifecycle()
    val actionHandlers = remember(viewModel) {
        AiActionHandlers(
            onAccept = viewModel::acceptAiAction,
            onDismiss = viewModel::dismissAiAction,
            onOpen = { src -> if (src.type == "message") viewModel.jumpTo(src.id) else onOpenSource(src) },
        )
    }

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
                    // 列表最下面可能还有等 AI 的提问、正在上传的附件，排在消息前面
                    val extra = pendingAi.size + uploads.size
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
            current.authorId == people.myUserId || wasAtBottom -> listState.scrollToItemMotion(0, reduceMotion)
            else -> unseen = true
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }
    // 刚问了 AI、刚选了附件：滚到最下面看着它
    var lastLocalItems by remember { mutableStateOf(0) }
    LaunchedEffect(pendingAi.size + uploads.size) {
        val count = pendingAi.size + uploads.size
        if (count > lastLocalItems) listState.scrollToItemMotion(0, reduceMotion)
        lastLocalItems = count
    }
    // 正在看（页面在前台、列表在底部）就推进未读位置
    LaunchedEffect(visible, atBottom, newestSeq, dividerAfter != null) {
        // 分隔线的位置记下之后再推进
        if (visible && atBottom && newestSeq > 0 && dividerAfter != null) viewModel.markRead(newestSeq)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ChatHeader(people = people, partnerMood = state.partnerMood, onSearch = viewModel::openSearch)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 聊天背景角落的绿萝（装饰，不压字：在列表下面）
            Sprig(Modifier.align(Alignment.TopEnd).offset(x = 18.dp, y = 18.dp).rotate(12f), width = 150.dp, flip = true, alpha = .55f)
            val maxBubble = (maxWidth - 40.dp) * 0.78f
            val zone = remember { ZoneId.systemDefault() }
            val today = remember { LocalDate.now(zone) }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 等 AI 回答的提问在最下面（倒序列表的最前面）
                items(pendingAi.asReversed(), key = { "ai-${it.jobId}" }) { pending ->
                    PendingAiItem(
                        pending, askerName = people.name(people.myUserId),
                        onRetry = { viewModel.retryAi(pending.jobId) }, onDismiss = { viewModel.dismissAi(pending.jobId) },
                        onStop = { viewModel.stopAi(pending.jobId) },
                    )
                }
                // 正在上传的附件，最新的最靠下
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
                        unreadDivider = dividerAfter?.let { after ->
                            val m = local.value
                            m.createdSeq > after && (older == null || older.createdSeq in 1..after) && m.authorId != people.myUserId
                        } == true,
                        onRetry = viewModel::retry, onAbandon = viewModel::abandon,
                        onLongPress = { menuFor = local }, onQuoteClick = viewModel::jumpTo,
                        aiActions = aiActions[local.value.id].orEmpty(), actionHandlers = actionHandlers,
                        onOpenSource = { src -> if (src.type == "message") viewModel.jumpTo(src.id) else onOpenSource(src) },
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
                    onClick = { scope.launch { listState.scrollToItemMotion(0, reduceMotion) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp),
                )
            }
        }
        // 离线：在输入框上方标出来（按 Chat.dc.html）
        if (!state.online) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(colors.accent))
                Text("离线", style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, letterSpacing = 0.2.em, color = colors.accent))
            }
        }
        replyTo?.let { ReplyStrip(it, people, onCancel = viewModel::cancelReply) }
        InputBar(
            draft = draft,
            online = state.online,
            aiEnabled = state.aiEnabled,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onAskAi = viewModel::askAi,
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
    val captioning by viewModel.captioning.collectAsStateWithLifecycle()
    captioning?.let { upload -> PhotoCaptionSheet(upload, onSend = viewModel::sendPhoto, onCancel = viewModel::cancelPhoto) }

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
            onArchive = { onArchive(target.value) },
            onOrganize = if (state.aiEnabled) ({ viewModel.organize(target.value) }) else null,
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

/**
 * 聊天页顶栏（主页的收起形态，按 New-Chat）：对方圆标 + 名字，名字下一句手写的今天心情；右边搜索。
 * 房间里还只有自己时写「聊天」。
 */
@Composable
private fun ChatHeader(people: People, partnerMood: Mood?, onSearch: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val partner = people.partner
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, top = topBarInset(), end = Spacing.sm, bottom = Spacing.xs)
            .heightIn(min = Sizes.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (partner != null) PersonMark(people.markChar(partner.userId), people.person(partner.userId), size = 30.dp)
        Column(Modifier.weight(1f)) {
            Text(
                partner?.displayName ?: "聊天",
                style = type.barTitle.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (partnerMood != null) HandNote("今天" + feelingWord(partnerMood.label, partnerMood.intensity), fontSizeSp = 16f, rotation = -2f)
        }
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
    unreadDivider: Boolean,
    onRetry: (Message) -> Unit,
    onAbandon: (Message) -> Unit,
    onLongPress: () -> Unit,
    onQuoteClick: (UUID) -> Unit,
    onOpenSource: (SummarySource) -> Unit,
    aiActions: List<AiAction>,
    actionHandlers: AiActionHandlers,
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
        if (unreadDivider) {
            NewMessagesDivider()
            Box(Modifier.size(16.dp))
        }
        Column(Modifier.background(flash, RoundedCornerShape(8.dp))) {
            MessageBody(local, mine, groupedWithNewer, people, zone, maxBubble, onRetry, onAbandon, onLongPress, onQuoteClick, onOpenSource, aiActions, actionHandlers, attachments)
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
    onOpenSource: (SummarySource) -> Unit,
    aiActions: List<AiAction>,
    actionHandlers: AiActionHandlers,
    attachments: AttachmentActions,
) {
    val m = local.value
    val colors = QichiTheme.colors
    when {
        m.retractedAt != null -> Notice(if (m.retractedBy == people.myUserId) "你撤回了一条消息" else "${people.name(m.retractedBy)}撤回了一条消息")
        m.kind == MessageKind.System -> Notice(m.body)
        m.kind == MessageKind.Ai -> AiBlock(
            prompt = m.aiPrompt, asker = aiAsker(m, people),
            modifier = Modifier.combinedClickable(onClick = {}, onLongClickLabel = "更多操作", onLongClick = onLongPress),
        ) {
            AiAnswer(m, onOpenSource)
            AiActionCards(aiActions, people, zone, actionHandlers)
        }
        else -> {
            val file = m.file
            when {
                file != null && m.kind == MessageKind.Image -> AttachmentRow(local, mine) { _, _ ->
                    // 照片是拍立得：相框下面手写说明，微微倾斜，对方发的贴一条雾蓝胶带
                    Polaroid(
                        Modifier.padding(start = if (mine) 0.dp else 6.dp, end = if (mine) 6.dp else 0.dp, top = 6.dp, bottom = 2.dp),
                        caption = m.body.ifBlank { null },
                        rotation = if (mine) 2f else -3f,
                        tape = { Tape(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp), color = if (mine) colors.personA else colors.personB, width = 52.dp, rotation = 4f) },
                    ) {
                        ImageBubble(file, RectangleShape, attachments.urls, onOpen = { attachments.onOpenImage(file) }, onLongPress = onLongPress)
                    }
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
        content(shape, if (mine) Modifier.background(colors.background).background(colors.personA.copy(alpha = 0.16f)) else Modifier.background(colors.card))
    }
}

private fun bubbleShape(mine: Boolean): Shape =
    if (mine) RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

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
                .then(if (!mine && !unsent) Modifier.liftFlat(colors, shape) else Modifier)
                .clip(shape)
                .then(
                    when {
                        unsent -> Modifier.background(colors.background).border(1.3.dp, if (local.isFailed) colors.accent else colors.personA, shape)
                        mine -> Modifier.background(colors.background).background(colors.personA.copy(alpha = 0.16f))
                        else -> Modifier.background(colors.card)
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
                .padding(horizontal = 15.dp, vertical = 10.dp),
        ) {
            if (m.replyToId != null || m.replyExcerpt != null) ReplyQuote(m, people, onClick = m.replyToId?.let { id -> { onQuoteClick(id) } })
            Text(m.body, style = type.body.copy(lineHeight = 24.75.tsp, color = colors.ink))
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
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (m.replyAuthorId != null) PersonMark(people.markChar(m.replyAuthorId), people.person(m.replyAuthorId), size = 16.dp)
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
        style = QichiTheme.typography.numeral.copy(fontSize = 11.tsp, color = QichiTheme.colors.muted),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp),
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

/** 谁问的 AI（旧版服务端的回答没有这一项时为空，只写「问：」）。 */
private fun aiAsker(m: Message, people: People): String? = m.aiAskedBy?.let { people.name(it) }

/** AI 的回答（按 New-Chat）：左边「✦AI」+ 小字「谁问：问题」，下面是整宽的回答正文。 */
@Composable
private fun AiBlock(prompt: String?, modifier: Modifier = Modifier, asker: String? = null, content: @Composable () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiMark()
            if (!prompt.isNullOrBlank()) {
                Text(
                    (asker?.let { "${it}问：" } ?: "问：") + prompt,
                    style = type.caption.copy(fontSize = 12.tsp, color = colors.muted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        content()
    }
}

/**
 * AI 回答：正文里的 [n] 是链接，点了打开引用的那条记录；有引用时下面可以展开「参考了几条资料」。
 * 服务端只存正文里真的出现过的编号，找不到对应来源的 [n] 按普通文字显示。
 */
@Composable
private fun AiAnswer(m: Message, onOpenSource: (SummarySource) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val byNumber = remember(m.aiSources) { m.aiSources.associateBy { it.number } }
    val linkStyle = TextLinkStyles(
        SpanStyle(
            color = colors.personB, background = colors.personB.copy(alpha = .14f),
            fontFamily = type.numeral.fontFamily, fontSize = 11.tsp, fontWeight = FontWeight.W500,
        ),
    )
    val text = remember(m.body, byNumber, linkStyle) {
        buildAnnotatedString {
            var last = 0
            citationPattern.findAll(m.body).forEach { match ->
                append(m.body.substring(last, match.range.first))
                val src = byNumber[match.groupValues[1].toInt()]
                if (src == null) {
                    append(match.value)
                } else {
                    // [n] 显示成「 n 」小标签（前后细空格撑出圆角的样子）
                    // 前面留一个窄空格（不带底色），连着的几个编号才不会粘成一块
                    append("\u202F")
                    withLink(LinkAnnotation.Clickable("source-${src.number}", linkStyle) { onOpenSource(src) }) { append("\u2009${src.number}\u2009") }
                }
                last = match.range.last + 1
            }
            append(m.body.substring(last))
        }
    }
    if (m.body.isNotEmpty()) Text(text, style = aiAnswerStyle(), modifier = Modifier.fillMaxWidth())
    if (m.aiStopped) {
        Text(
            if (m.body.isEmpty()) "已停下，还没写出内容" else "已停下",
            style = type.caption.copy(fontSize = 12.tsp, color = colors.muted),
        )
    }
    if (m.aiSources.isNotEmpty()) {
        var open by rememberSaveable(m.id) { mutableStateOf(false) }
        TextAction(
            "参考了 ${m.aiSources.size} 条资料" + if (open) " · 收起" else "",
            onClick = { open = !open },
            color = colors.muted,
        )
        if (open) {
            m.aiSources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开原来的记录") { onOpenSource(src) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Box(Modifier.widthIn(min = 32.dp)) { RefChip(src.number) }
                    Text(sourceKind(src.type), style = type.caption.copy(color = colors.faint))
                    Text(sourceLabel(src), style = type.caption.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val citationPattern = Regex("\\[(\\d{1,3})]")

@Composable
private fun aiAnswerStyle() = QichiTheme.typography.body.copy(fontSize = 15.tsp, lineHeight = 26.25.tsp, color = QichiTheme.colors.ink)

/**
 * 还在等的 AI 提问（按 New-Chat-Streaming）：雾蓝淡底的浮起卡片，右上两颗小星；
 * 边写边显示，下面「三个点 · 正在写」和「停下」。失败时「没有得到回答 · 重试」。
 */
@Composable
private fun PendingAiItem(pending: PendingAi, askerName: String, onRetry: () -> Unit, onDismiss: () -> Unit, onStop: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .lift(colors, shape)
            .clip(shape)
            .background(colors.card)
            .background(colors.personB.copy(alpha = .08f))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(Modifier.align(Alignment.TopEnd).alpha(.6f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(QichiIcons.Spark, contentDescription = null, tint = colors.personB, modifier = Modifier.size(12.dp))
            Icon(QichiIcons.Spark, contentDescription = null, tint = colors.personB, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiBlock(prompt = pending.prompt, asker = askerName, modifier = Modifier.padding(end = 40.dp)) {}
            when {
                pending.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("没有得到回答", style = type.caption.copy(color = colors.accent))
                    TextAction("重试", onClick = onRetry)
                    TextAction("算了", onClick = onDismiss, color = colors.muted)
                }
                else -> {
                    // 边生成边显示（P8-03）：引用编号和提议卡片等正式回答同步下来再出现
                    if (!pending.partial.isNullOrEmpty()) Text(pending.partial, style = aiAnswerStyle(), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThinkingDots()
                        Text(
                            when {
                                pending.stopping -> "正在停下"
                                pending.partial.isNullOrEmpty() -> "正在想"
                                else -> "正在写"
                            },
                            style = type.caption.copy(fontSize = 12.tsp, color = colors.muted),
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            Modifier
                                .heightIn(min = 40.dp)
                                .clip(QichiShapes.pill)
                                .background(colors.surface)
                                .border(1.dp, colors.ink.copy(alpha = .08f), QichiShapes.pill)
                                .clickable(enabled = !pending.stopping, role = Role.Button, onClickLabel = "停下 AI 的回答", onClick = onStop)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(QichiIcons.Stop, contentDescription = null, tint = if (pending.stopping) colors.faint else colors.ink, modifier = Modifier.size(14.dp))
                            Text("停下", style = type.button.copy(fontSize = 14.tsp, letterSpacing = 0.em, color = if (pending.stopping) colors.faint else colors.ink))
                        }
                    }
                }
            }
        }
    }
}

/** 设计稿里的「新消息」：两侧 accent 细线。 */
@Composable
private fun NewMessagesDivider() {
    val colors = QichiTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).dashedLine(colors.accent.copy(alpha = 0.5f)))
        Text("新消息", style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, fontWeight = FontWeight.W500, color = colors.accent))
        Box(Modifier.weight(1f).height(1.dp).dashedLine(colors.accent.copy(alpha = 0.5f)))
    }
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

/** 长按消息：回复、复制、存进档案、让 AI 整理、撤回（自己的）、删除。待发送或发送失败的消息只能复制。 */
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
    onArchive: () -> Unit,
    /** 「让 AI 整理」；AI 没开时为空 */
    onOrganize: (() -> Unit)?,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val m = local.value
    val synced = !local.isPending && !local.isFailed
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 28.dp)) {
            Text(
                "${if (m.kind == MessageKind.Ai) "AI" else people.name(m.authorId)}：${m.body}",
                style = type.caption.copy(color = colors.muted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (synced) ActionRow("回复") { onReply(); onDismiss() }
            if (m.body.isNotEmpty()) ActionRow("复制") { onCopy(); onDismiss() }
            if (synced && m.body.isNotEmpty() && m.retractedAt == null) ActionRow("存进档案") { onArchive(); onDismiss() }
            if (onOrganize != null && synced && m.kind == MessageKind.Text && m.body.isNotBlank() && m.retractedAt == null) {
                ActionRow("让 AI 整理") { onOrganize(); onDismiss() }
            }
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
private fun InputBar(
    draft: String,
    online: Boolean,
    aiEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAskAi: () -> Unit,
    onAttach: () -> Unit,
) {
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
            onClick = onAttach, iconSize = 22, tint = if (online) colors.ink else colors.faint,
        )
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .lift(colors, RoundedCornerShape(23.dp))
                .clip(RoundedCornerShape(23.dp))
                .background(colors.card)
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = type.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.ink),
                    maxLines = 5,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "消息" },
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text("说点什么", style = type.body.copy(color = colors.faint))
                        inner()
                    },
                )
                // 设计稿里输入框右端的「问 AI」：只有点它才会调用 AI；没开启、离线、没写问题时置灰
                val canAsk = aiEnabled && online && draft.isNotBlank()
                val askColor = if (canAsk) colors.personB else colors.faint
                Row(
                    Modifier
                        .heightIn(min = 24.dp)
                        .clickable(role = Role.Button, onClick = onAskAi)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(QichiIcons.Spark, contentDescription = null, tint = askColor, modifier = Modifier.size(15.dp))
                    Text("问 AI", style = type.body.copy(fontSize = 14.tsp, fontWeight = FontWeight.W500, color = askColor))
                }
            }
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

/** 1dp 虚线（「新消息」两侧）。 */
private fun Modifier.dashedLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
}
