package app.qichi.feature.me

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.AccountRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.SwitchRow
import app.qichi.shared.api.AiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AiPrefsViewModel @Inject constructor(
    private val account: AccountRepository,
    rooms: RoomRepository,
) : ViewModel() {
    /** null = 还没从服务端拿到 */
    val prefs: StateFlow<AiPrefs?> = rooms.me.map { me -> me?.user?.aiPrefs?.let(AiPrefs::from) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun save(p: AiPrefs) = viewModelScope.launch {
        try {
            account.updateAiPrefs(p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _message.value = "没能保存，改这个设置需要联网"
        }
    }

    fun messageShown() { _message.value = null }
}

/**
 * AI 能看什么：问 AI、AI 出题、总结时，可以参考哪些房间资料。默认都能看。
 * 两个人都允许的类别 AI 才看得到（P11）：自己关掉的，谁发起的 AI 请求都看不到。
 */
@Composable
fun AiPrefsScreen(onBack: () -> Unit, vm: AiPrefsViewModel = hiltViewModel()) {
    val saved by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.messageShown() } }
    val p = saved ?: AiPrefs()

    Column(Modifier.fillMaxSize().background(colors.background)) {
        ItemTopBar("AI 能看什么", onBack, feature = Feature.Me)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(
                "你们问 AI、让 AI 出题或写总结时，它会在房间里查相关的资料再回答，并标出参考了哪几条。",
                style = type.caption.copy(color = colors.muted),
                modifier = Modifier.padding(top = Spacing.s),
            )
            Text(
                "两个人都允许的类别 AI 才看得到：你关掉的，对方问 AI 时也看不到；对方关掉的也一样。",
                style = type.caption.copy(color = colors.ink),
                modifier = Modifier.padding(top = Spacing.xs),
            )
            @Composable
            fun toggle(label: String, hint: String?, value: Boolean, change: (Boolean) -> AiPrefs) =
                SwitchRow(label, value, { vm.save(change(it)) }, description = hint, enabled = saved != null)
            SectionLabel("聊天", modifier = Modifier.padding(top = Spacing.l))
            toggle("更早的聊天", "最近的几十条聊天总会带上，这是问 AI 的背景", p.chat) { p.copy(chat = it) }
            SectionLabel("生活", modifier = Modifier.padding(top = Spacing.l))
            toggle("心情", "包括对方的回应", p.moods) { p.copy(moods = it) }
            toggle("问答", "只有两个人都确认、已经揭晓的回答", p.qna) { p.copy(qna = it) }
            toggle("计划", "阶段、里程碑和进展", p.plans) { p.copy(plans = it) }
            toggle("待办", null, p.todos) { p.copy(todos = it) }
            toggle("日程", null, p.events) { p.copy(events = it) }
            toggle("灵感", null, p.ideas) { p.copy(ideas = it) }
            SectionLabel("创作", modifier = Modifier.padding(top = Spacing.l))
            toggle("写作", "保存过的文稿和段落旁的留言；没保存的草稿不会给", p.writing) { p.copy(writing = it) }
            toggle("留言板", null, p.board) { p.copy(board = it) }
            SectionLabel("回看", modifier = Modifier.padding(top = Spacing.l))
            toggle("档案", "偏好、共识、界限……", p.archive) { p.copy(archive = it) }
            toggle("决定", "备选和各自在意的点", p.decisions) { p.copy(decisions = it) }
            toggle("阅读", "书架、进度和公开的摘录；没公开的不会给", p.reading) { p.copy(reading = it) }
            toggle("审稿", "批注、讨论和 AI 审稿发现", p.review) { p.copy(review = it) }
            toggle("总结", null, p.summaries) { p.copy(summaries = it) }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
