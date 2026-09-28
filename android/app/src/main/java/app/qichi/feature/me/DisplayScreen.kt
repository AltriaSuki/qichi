package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.DisplaySettings
import app.qichi.core.data.DisplaySettingsStore
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.SkyMode
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DisplayViewModel @Inject constructor(private val store: DisplaySettingsStore) : ViewModel() {
    val settings: StateFlow<DisplaySettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySettings())

    fun setLargeText(on: Boolean) = viewModelScope.launch { store.setLargeText(on) }
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { store.setReduceMotion(on) }
    fun setSkyMode(mode: SkyMode) = viewModelScope.launch { store.setSkyMode(mode) }
}

/** 「我的 → 显示」：天色、字号（标准 / 大字）与减少动画。改了立即作用于整个 App，这一页本身就是预览。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayScreen(
    onBack: () -> Unit,
    viewModel: DisplayViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ItemTopBar("显示", onBack, feature = Feature.Me)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            Column {
                SectionLabel("天色")
                // 默认跟着时间变四种天色；想要一直浅色、一直深色，或跟手机的深色模式走，在这里选
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    SkyMode.entries.forEach { mode ->
                        ChoicePill(mode.label, selected = settings.skyMode == mode, onClick = { viewModel.setSkyMode(mode) })
                    }
                }
                Text(
                    when (settings.skyMode) {
                        SkyMode.Auto -> "清晨、白天、黄昏、深夜按手机时间自动换。"
                        SkyMode.System -> "手机开了深色模式就是深夜的颜色；没开时按时间换，夜里也用白天的颜色。"
                        SkyMode.Light -> "一直是白天的颜色。"
                        SkyMode.Dark -> "一直是深夜的颜色。"
                    },
                    style = type.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Column {
                SectionLabel("字号")
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    val pill = Modifier.widthIn(min = 76.dp)
                    ChoicePill("标准", selected = !settings.largeText, onClick = { viewModel.setLargeText(false) }, modifier = pill)
                    ChoicePill("大字", selected = settings.largeText, onClick = { viewModel.setLargeText(true) }, modifier = pill)
                }
                MistCard(Modifier.padding(top = Spacing.m), contentPadding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text("很安宁", style = type.feeling.copy(color = colors.ink))
                        Text("7", style = type.numeral.copy(color = colors.muted), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text("周末去河边走了很久，风很软。", style = type.body.copy(color = colors.ink), modifier = Modifier.padding(top = Spacing.xs))
                    Text("大字模式下所有文字放大到 1.2 倍", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.s))
                }
            }
            Column {
                SectionLabel("动画")
                SwitchRow(
                    label = "减少动画",
                    description = "天色变化与页面切换直接完成，不再淡入淡出",
                    checked = settings.reduceMotion,
                    onCheckedChange = viewModel::setReduceMotion,
                )
            }
        }
    }
}
