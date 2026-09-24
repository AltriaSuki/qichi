package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiUsage
import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiJobStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiUsageState(
    val month: YearMonth = YearMonth.now(),
    val usage: AiUsage? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AiUsageViewModel @Inject constructor(private val rooms: RoomRepository) : ViewModel() {
    private val _state = MutableStateFlow(AiUsageState())
    val state: StateFlow<AiUsageState> = _state.asStateFlow()

    init {
        load(_state.value.month)
    }

    fun previous() = load(_state.value.month.minusMonths(1))

    fun next() {
        if (_state.value.month < YearMonth.now()) load(_state.value.month.plusMonths(1))
    }

    private fun load(month: YearMonth) {
        _state.update { it.copy(month = month, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val usage = rooms.aiUsage(month)
                _state.update { if (it.month == month) it.copy(usage = usage, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(usage = null, loading = false, error = "联网后才能看到用量") }
            }
        }
    }
}

private val JOB_TIME = DateTimeFormatter.ofPattern("M · d  HH:mm")

/** 「我的 → 我发起的 AI 使用」：按月列出自己发起的 AI 调用，以及这个月两个人一共用了多少。 */
@Composable
fun AiUsageScreen(onBack: () -> Unit, viewModel: AiUsageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val numbers = remember { NumberFormat.getIntegerInstance(Locale.ROOT) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        ItemTopBar("我发起的 AI 使用", onBack, feature = Feature.Me)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.page - 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(QichiIcons.Back, contentDescription = "上个月", onClick = viewModel::previous, iconSize = 18)
            Text(
                "${state.month.year} · ${state.month.monthValue}",
                style = type.numeral.copy(fontSize = 22.tsp, color = colors.ink),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconAction(
                QichiIcons.Forward, contentDescription = "下个月", onClick = viewModel::next, iconSize = 18,
                enabled = state.month < YearMonth.now(),
            )
        }
        val usage = state.usage
        when {
            usage == null && state.loading -> Hint("正在读取…")
            usage == null -> Hint(state.error ?: "")
            else -> LazyColumn(contentPadding = PaddingValues(start = Spacing.page, end = Spacing.page, top = Spacing.m, bottom = 32.dp)) {
                item {
                    Text(
                        if (usage.jobs.isEmpty()) "这个月还没有问过 AI" else "这个月问了 ${usage.jobs.size} 次，用量 ${numbers.format(usage.myInputTokens + usage.myOutputTokens)}",
                        style = type.bodyLarge.copy(color = colors.ink),
                    )
                    MonthQuota(usage)
                }
                if (usage.jobs.isNotEmpty()) item { SectionLabel("明细", modifier = Modifier.padding(top = Spacing.l)) }
                items(usage.jobs, key = { it.id }) { job -> JobRow(job, numbers) }
            }
        }
    }
}

/** 本月额度：两个人加起来用了多少，细线表示比例。 */
@Composable
private fun MonthQuota(usage: AiUsage) {
    val colors = QichiTheme.colors
    val numbers = remember { NumberFormat.getIntegerInstance(Locale.ROOT) }
    val ratio = if (usage.monthLimitTokens > 0) (usage.monthUsedTokens.toFloat() / usage.monthLimitTokens).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = Spacing.m)) {
        Text(
            "本月两人共用 ${numbers.format(usage.monthUsedTokens)} / ${numbers.format(usage.monthLimitTokens)}",
            style = QichiTheme.typography.caption.copy(color = colors.muted),
        )
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(colors.line)) {
            Box(Modifier.fillMaxWidth(ratio).height(2.dp).background(if (ratio >= 0.9f) colors.accent else colors.personB))
        }
    }
}

@Composable
private fun JobRow(job: AiJob, numbers: NumberFormat) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val zone = remember { ZoneId.systemDefault() }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(kindLabel(job.kind), style = type.bodyLarge.copy(color = colors.ink))
            Text(
                "${JOB_TIME.format(job.createdAt.atZone(zone))}  ${statusLabel(job.status)}",
                style = type.caption.copy(color = if (job.status == AiJobStatus.Failed) colors.accent else colors.muted),
            )
        }
        Text(numbers.format(job.inputTokens + job.outputTokens), style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = QichiTheme.typography.caption.copy(color = QichiTheme.colors.muted), modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.m))
}

private fun kindLabel(kind: AiJobKind): String = when (kind) {
    AiJobKind.ChatAnswer -> "聊天里问 AI"
    AiJobKind.QuestionSuggest -> "请 AI 出题"
    AiJobKind.ReadExplain -> "阅读时请 AI 解释"
    AiJobKind.ReviewFindings -> "审稿发现"
    AiJobKind.Summary -> "总结"
    AiJobKind.YearlyReview -> "年度回顾"
    AiJobKind.WriteAssist -> "写作时请 AI 帮忙"
}

private fun statusLabel(status: AiJobStatus): String = when (status) {
    AiJobStatus.Queued, AiJobStatus.Running -> "进行中"
    AiJobStatus.Done -> "完成"
    AiJobStatus.Failed -> "没有得到回答"
}
