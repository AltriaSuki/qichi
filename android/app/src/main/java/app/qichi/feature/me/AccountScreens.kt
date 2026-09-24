package app.qichi.feature.me

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.qichi.BuildConfig
import app.qichi.core.push.PushNotifier
import app.qichi.core.push.PushRegistrar
import org.unifiedpush.android.connector.UnifiedPush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.AccountRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.database.TypeCount
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.ApiException
import app.qichi.core.ui.relativeDay
import app.qichi.navigation.Page
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.Decision
import app.qichi.shared.api.Document
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.Event
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Idea
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.Message
import app.qichi.shared.api.Mood
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.api.Plan
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.Todo
import app.qichi.shared.api.Answer
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

// ───────────────────────── 我写下的内容 ─────────────────────────

/** 能按作者归总的内容：种类、显示名、点进去去哪一页（null = 聊天）。 */
private data class ContentKind(val type: EntityType, val label: String, val page: Page?)

private val contentKinds = listOf(
    ContentKind(EntityType.Message, "聊天消息", null),
    ContentKind(EntityType.Mood, "心情", Page.Mood),
    ContentKind(EntityType.Answer, "问答的回答", Page.Qna),
    ContentKind(EntityType.Todo, "待办", Page.Todo),
    ContentKind(EntityType.Event, "日程", Page.Calendar),
    ContentKind(EntityType.Plan, "负责的计划", Page.Plan),
    ContentKind(EntityType.Idea, "灵感", Page.Ideas),
    ContentKind(EntityType.BoardPost, "留言", Page.Board),
    ContentKind(EntityType.Document, "发起的文稿", Page.Writing),
    ContentKind(EntityType.ArchiveItem, "档案", Page.Archive),
    ContentKind(EntityType.Decision, "决定", Page.Decisions),
    ContentKind(EntityType.Highlight, "书里的标记", Page.Reading),
)

/** 最近写下的一条：种类、摘要、时间。 */
data class MyItem(val type: EntityType, val text: String, val at: Instant)

data class MyContentState(val counts: List<TypeCount> = emptyList(), val recent: List<MyItem> = emptyList(), val zone: ZoneId = ZoneId.systemDefault())

private fun summaryOf(type: EntityType, json: String): MyItem? = runCatching {
    when (val e = EntityCodec.decode(type, QichiJson.parseToJsonElement(json))) {
        is Message -> MyItem(type, e.body.ifBlank { e.file?.fileName ?: "（附件）" }, e.createdAt)
        is Mood -> MyItem(type, listOfNotNull(app.qichi.core.ui.feelingWord(e.label, e.intensity), e.note).joinToString(" "), e.createdAt)
        is Answer -> MyItem(type, e.body, e.createdAt)
        is Todo -> MyItem(type, e.title, e.createdAt)
        is Event -> MyItem(type, e.title, e.createdAt)
        is Plan -> MyItem(type, e.title, e.createdAt)
        is Idea -> MyItem(type, e.body, e.createdAt)
        is BoardPost -> MyItem(type, e.body, e.createdAt)
        is Document -> MyItem(type, e.title, e.createdAt)
        is ArchiveItem -> MyItem(type, e.title, e.createdAt)
        is Decision -> MyItem(type, e.question, e.createdAt)
        is Highlight -> MyItem(type, e.text.ifBlank { "书签" }, e.createdAt)
        else -> null
    }
}.getOrNull()

@HiltViewModel(assistedFactory = MyContentViewModel.Factory::class)
class MyContentViewModel @AssistedInject constructor(
    @Assisted roomId: UUID,
    account: AccountRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val types = contentKinds.map { it.type.wireName }
    private val me = session.currentUserId

    val state: StateFlow<MyContentState> = (if (me == null) flowOf(MyContentState()) else combine(
        account.observeMyCounts(roomId, me, types), account.observeMyRecent(roomId, me, types), rooms.observeRoom(roomId),
    ) { counts, rows, room ->
        MyContentState(counts, rows.mapNotNull { summaryOf(fromWire(it.type), it.json) }, app.qichi.core.ui.zoneOf(room?.timezone))
    }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyContentState())

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): MyContentViewModel
    }
}


/** 我写下的内容：按种类归总（本机已有的），以及最近写下的几条。点种类去对应的页面。 */
@Composable
fun MyContentScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (Page) -> Unit,
    onOpenChat: () -> Unit,
    vm: MyContentViewModel = hiltViewModel<MyContentViewModel, MyContentViewModel.Factory>(key = "my-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val labels = contentKinds.associateBy { it.type }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("我写下的内容", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text("这台手机上已有的内容。聊天记录只算已经同步到手机上的。", style = type.caption.copy(color = colors.muted))
            val byType = state.counts.associate { it.type to it.count }
            contentKinds.forEach { kind ->
                val n = byType[kind.type.wireName] ?: 0
                if (n > 0) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { kind.page?.let(onOpen) ?: onOpenChat() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kind.label, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
                        Text(n.toString(), style = type.numeral.copy(fontSize = 19.tsp, color = colors.muted))
                    }
                }
            }
            if (state.counts.isEmpty()) Text("还没有写下什么。", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.m))
            if (state.recent.isNotEmpty()) {
                SectionLabel("最近写下的", modifier = Modifier.padding(top = Spacing.l))
                val today = java.time.LocalDate.now(state.zone)
                state.recent.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Text("${labels[item.type]?.label ?: ""} · ${relativeDay(item.at.atZone(state.zone).toLocalDate(), today).first}", style = type.caption.copy(color = colors.faint))
                        Text(item.text, style = type.body.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

// ───────────────────────── 安全 ─────────────────────────

data class SecurityState(
    val sessions: List<LoginSession>? = null,
    val failed: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(SecurityState())
    val state: StateFlow<SecurityState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.value = _state.value.copy(sessions = account.sessions(), failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = _state.value.copy(failed = true)
        }
    }

    fun revoke(s: LoginSession) = viewModelScope.launch {
        try {
            account.revoke(s.id)
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = _state.value.copy(message = "没能退出那台设备，检查一下网络")
        }
    }

    /** 让除这台以外的设备都退出登录。 */
    fun revokeOthers() = viewModelScope.launch {
        val others = _state.value.sessions.orEmpty().filterNot { it.current }
        try {
            others.forEach { account.revoke(it.id) }
            _state.value = _state.value.copy(message = "其它设备都已退出")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = _state.value.copy(message = "没能全部退出，检查一下网络")
        }
        load()
    }

    fun changePassword(current: String, new: String, onDone: () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(saving = true)
        try {
            account.changePassword(current, new)
            _state.value = _state.value.copy(saving = false, message = "密码改好了，其它设备需要重新登录")
            onDone()
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _state.value = _state.value.copy(saving = false, message = if (e.status == 401) "当前密码不对" else e.userMessage)
        } catch (_: Exception) {
            _state.value = _state.value.copy(saving = false, message = "没能改密码，检查一下网络")
        }
    }

    fun messageShown() { _state.value = _state.value.copy(message = null) }
}

private val deviceTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/** 安全：登录设备（可以让某台退出）、改密码（改完其它设备都要重新登录）。都需要联网。 */
@Composable
fun SecurityScreen(onBack: () -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var revoking by remember { mutableStateOf<LoginSession?>(null) }
    var revokingAll by remember { mutableStateOf(false) }
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.message) { state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }
    val zone = ZoneId.systemDefault()

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        BackBar("安全", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            SectionLabel("登录的设备") {
                if ((state.sessions?.count { !it.current } ?: 0) > 1) TextAction("让其它设备都退出", { revokingAll = true }, color = colors.accent)
            }
            when {
                state.sessions == null && state.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("需要联网才能看。", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                    TextAction("再试一次", vm::load)
                }
                state.sessions == null -> Text("正在取…", style = type.caption.copy(color = colors.faint))
                else -> state.sessions!!.forEach { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((s.deviceName ?: "一台设备") + if (s.current) "（这台）" else "", style = type.bodyLarge.copy(color = colors.ink))
                            Text("最近使用 ${s.lastUsedAt.atZone(zone).format(deviceTime)} · 登录于 ${s.createdAt.atZone(zone).format(deviceTime)}", style = type.caption.copy(color = colors.muted))
                        }
                        if (!s.current) TextAction("退出", { revoking = s }, color = colors.accent)
                    }
                }
            }

            SectionLabel("改密码", modifier = Modifier.padding(top = Spacing.l))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                QichiTextField(current, { current = it }, label = "当前密码", password = true)
                QichiTextField(new, { new = it }, label = "新密码（8 位以上）", password = true,
                    error = if (new.isNotEmpty() && new.length !in Limits.PASSWORD_LENGTH) "8–128 位" else null)
                QichiTextField(again, { again = it }, label = "再输一次新密码", password = true,
                    error = if (again.isNotEmpty() && again != new) "两次不一样" else null)
                Text("改完之后，其它设备都要重新登录。", style = type.caption.copy(color = colors.muted))
                PrimaryButton(
                    if (state.saving) "正在改…" else "改密码",
                    { vm.changePassword(current, new) { current = ""; new = ""; again = "" } },
                    enabled = !state.saving && current.isNotEmpty() && new.length in Limits.PASSWORD_LENGTH && new == again,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
    if (revokingAll) {
        ConfirmDialog("让其它设备都退出？", "除了这台手机，其它登录都要重新登录。", "都退出", onConfirm = { vm.revokeOthers(); revokingAll = false }, onDismiss = { revokingAll = false })
    }
    revoking?.let { s ->
        ConfirmDialog("让这台设备退出登录？", "${s.deviceName ?: "那台设备"}需要重新登录才能再用。", "退出", onConfirm = { vm.revoke(s); revoking = null }, onDismiss = { revoking = null })
    }
}

// ───────────────────────── 通知 ─────────────────────────

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val account: AccountRepository,
    rooms: RoomRepository,
    private val push: PushRegistrar,
    private val session: SessionManager,
) : ViewModel() {
    /** 非空 = 推送已开启。 */
    val pushEndpoint: StateFlow<String?> = push.endpoint

    fun pushTurnedOff() = viewModelScope.launch { push.onUnregistered(session.currentUserId != null) }

    val prefs: StateFlow<NotificationPrefs?> = kotlinx.coroutines.flow.combine(rooms.me, flowOf(Unit)) { me, _ -> me?.user?.notificationPrefs?.let(NotificationPrefs::from) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun save(p: NotificationPrefs) = viewModelScope.launch {
        try {
            account.updateNotifications(p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _message.value = "没能保存，改通知设置需要联网"
        }
    }

    fun messageShown() { _message.value = null }
}

/** 通知：开启推送、各类提醒的开关、通知里显示不显示内容、免打扰时段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val saved by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = 开始时间，false = 结束时间
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }
    val p = saved ?: NotificationPrefs()

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("通知", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            PushSection(vm)
            SectionLabel("提醒我", modifier = Modifier.padding(top = Spacing.l))
            @Composable
            fun toggle(label: String, value: Boolean, change: (Boolean) -> NotificationPrefs) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
                    Switch(value, { vm.save(change(it)) }, enabled = saved != null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                }
            }
            toggle("新消息", p.messages) { p.copy(messages = it) }
            toggle("心情与回应", p.moods) { p.copy(moods = it) }
            toggle("问答", p.qna) { p.copy(qna = it) }
            toggle("待办", p.todos) { p.copy(todos = it) }
            toggle("日程", p.events) { p.copy(events = it) }
            toggle("留言", p.board) { p.copy(board = it) }
            SectionLabel("内容", modifier = Modifier.padding(top = Spacing.l))
            toggle("通知里显示内容", p.showPreview) { p.copy(showPreview = it) }
            Text(
                if (p.showPreview) "像 QQ 那样，通知里能看到是谁、说了什么（锁屏上是否显示按手机的系统设置）。"
                else "通知里只写「谁做了什么」，看不到内容。",
                style = type.caption.copy(color = colors.muted),
            )
            SectionLabel("免打扰", modifier = Modifier.padding(top = Spacing.l))
            toggle("按时段免打扰", p.quietEnabled) { p.copy(quietEnabled = it) }
            if (p.quietEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("从 ${p.quietStart}", { picking = true }, color = colors.ink)
                    TextAction("到 ${p.quietEnd}", { picking = false }, color = colors.ink)
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
    picking?.let { start ->
        val (h, m) = (if (start) p.quietStart else p.quietEnd).split(":").map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
        val timeState = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextAction("确定", {
                    val t = "%02d:%02d".format(timeState.hour, timeState.minute)
                    vm.save(if (start) p.copy(quietStart = t) else p.copy(quietEnd = t))
                    picking = null
                })
            },
            dismissButton = { TextAction("取消", { picking = null }, color = colors.muted) },
            title = { Text(if (start) "免打扰开始" else "免打扰结束", style = type.body) },
        ) { TimePicker(timeState) }
    }
}

/**
 * 后台推送（UnifiedPush）：手机上装了 ntfy 才能开。开启 = 请求通知权限 → 选分发器（只有 ntfy 时直接用）→ 注册，
 * ntfy 回调推送地址后 [PushRegistrar] 登记到服务端。
 */
@Composable
private fun PushSection(vm: NotificationsViewModel) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    val endpoint by vm.pushEndpoint.collectAsStateWithLifecycle()
    // 从系统设置或 ntfy 回来时重新看一眼
    var resumed by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val hasDistributor = remember(resumed, endpoint) { UnifiedPush.getDistributors(context).isNotEmpty() }
    val canNotify = remember(resumed) { PushNotifier.canNotify(context) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun register() {
        val activity = context as? android.app.Activity ?: return
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(context)
                hint = null
            } else {
                hint = "没有选好推送服务，再点一次试试"
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumed++
        if (granted) register() else hint = "没有通知权限就收不到提醒，可以在系统设置里打开"
    }

    SectionLabel("后台推送")
    val pushHost = BuildConfig.BASE_URL.substringAfter("://").substringBefore('/').substringBefore(':')
    val server = if (pushHost.contains('.') && !pushHost.first().isDigit()) "https://push.$pushHost" else "你的推送服务器地址"
    val status = when {
        endpoint != null && !canNotify -> "推送已开启，但系统通知被关掉了，收不到提醒。"
        endpoint != null -> "推送已开启。App 不在前台时，对方做的事会通知你。"
        !hasDistributor -> "要收到推送，先在手机上装 ntfy（F-Droid 或 GitHub 下载），在 ntfy 设置里把默认服务器改成 $server，再回来点「开启推送」。"
        else -> "推送还没有开启。"
    }
    Text(status, style = type.body.copy(color = colors.ink), modifier = Modifier.padding(top = Spacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        when {
            endpoint == null -> TextAction("开启推送", {
                if (!hasDistributor) {
                    hint = "还没找到 ntfy，装好后再点"
                } else if (android.os.Build.VERSION.SDK_INT >= 33 && !canNotify) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    register()
                }
            })
            else -> TextAction("关闭推送", {
                UnifiedPush.unregister(context)
                vm.pushTurnedOff()
            }, color = colors.muted)
        }
        if (endpoint != null && !canNotify) {
            TextAction("打开系统通知", {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            })
        }
    }
    hint?.let { Text(it, style = type.caption.copy(color = colors.accent)) }
    Text(
        "记得在系统设置里把 ntfy 和栖迟都加入「电池优化白名单 / 允许后台运行 / 自启动」，不然手机省电时会收不到。",
        style = type.caption.copy(color = colors.muted),
        modifier = Modifier.padding(top = Spacing.xs),
    )
}
