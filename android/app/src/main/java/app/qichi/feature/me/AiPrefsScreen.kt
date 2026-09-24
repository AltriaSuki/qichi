package app.qichi.feature.me

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.AccountRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.shared.api.AiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

/** AI 能看什么：问 AI、AI 出题、总结时，可以参考哪些房间资料。默认都能看；只管自己发起的请求。 */
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
        BackBar("AI 能看什么", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(
                "你问 AI、让 AI 出题或写总结时，它会先在房间里找相关的资料再回答，并标出参考了哪几条。" +
                    "关掉的类别，你发起的 AI 请求就看不到；对方的设置由对方自己决定。",
                style = type.caption.copy(color = colors.muted),
                modifier = Modifier.padding(top = Spacing.s),
            )
            SectionLabel("可以参考", modifier = Modifier.padding(top = Spacing.l))
            @Composable
            fun toggle(label: String, hint: String?, value: Boolean, change: (Boolean) -> AiPrefs) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = type.bodyLarge.copy(color = colors.ink))
                        if (hint != null) Text(hint, style = type.caption.copy(color = colors.faint))
                    }
                    Switch(value, { vm.save(change(it)) }, enabled = saved != null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                }
            }
            toggle("日程", "接下来两周的，以及和问题有关的", p.events) { p.copy(events = it) }
            toggle("待办", "没做完的，以及和问题有关的", p.todos) { p.copy(todos = it) }
            toggle("计划", null, p.plans) { p.copy(plans = it) }
            toggle("档案", "偏好、共识、界限……", p.archive) { p.copy(archive = it) }
            toggle("决定", null, p.decisions) { p.copy(decisions = it) }
            toggle("灵感", null, p.ideas) { p.copy(ideas = it) }
            toggle("心情", "最近三天的", p.moods) { p.copy(moods = it) }
            toggle("更早的聊天", "最近的几十条聊天总会带上，这是问 AI 的背景", p.chat) { p.copy(chat = it) }
        }
    }
}
