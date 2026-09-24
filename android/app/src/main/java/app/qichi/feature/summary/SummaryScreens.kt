package app.qichi.feature.summary

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.MarkdownView
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Summary
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.SummaryKind
import app.qichi.core.ui.sourceKind
import app.qichi.core.ui.sourceLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private fun rangeText(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) "${start.year} · ${start.monthValue}.${start.dayOfMonth} — ${end.monthValue}.${end.dayOfMonth}"
    else "${start.year}.${start.monthValue}.${start.dayOfMonth} — ${end.year}.${end.monthValue}.${end.dayOfMonth}"

private val SummaryKind.label: String
    get() = when (this) {
        SummaryKind.Week -> "一周"
        SummaryKind.Month -> "一个月"
        SummaryKind.Custom -> "一段时间"
        SummaryKind.Year -> "年度回顾"
    }

/**
 * 总结：生成本周、本月或自定义范围的回顾（AI 派生），列表里新的在前；
 * 点开看正文和编号来源，点来源跳回原来那条记录。年度回顾每年 1 月 1 日自动生成，不能删。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpenSource: (SummarySource) -> Unit,
    vm: SummaryViewModel = hiltViewModel<SummaryViewModel, SummaryViewModel.Factory>(key = "summary-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    fun toast(reason: String?) { reason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    open?.let { id ->
        state.summaries.firstOrNull { it.value.id.toString() == id }?.let { s ->
            SummaryDetail(s.value, onBack = { open = null }, onOpenSource = onOpenSource, onDelete = { vm.delete(s.value); open = null })
            return
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("总结", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text("让 AI 帮你们回顾一段时间：发生了什么、定下了什么、完成了什么。每句都标出依据，点得回去。",
                style = type.caption.copy(color = colors.muted))
            FlowRow(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("这一周", false, { toast(vm.generate(SummaryKind.Week, "这一周")) })
                ChoicePill("这个月", false, { toast(vm.generate(SummaryKind.Month, "这个月")) })
                ChoicePill("上个月", false, { toast(vm.generate(SummaryKind.Month, "上个月", anchor = state.today.minusMonths(1))) })
                ChoicePill("选一段时间", false, { picking = true })
            }
            state.pending.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = Spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("AI", style = type.numeral.copy(fontSize = 17.tsp, color = colors.personB))
                    Text(if (p.failed) "「${p.label}」没有写成" else "正在写「${p.label}」的回顾……", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                    if (p.failed) {
                        TextAction("重试", p.retry)
                        TextAction("算了", { vm.dismiss(p.jobId) }, color = colors.muted)
                    }
                }
            }
            if (state.loaded && state.summaries.isEmpty() && state.pending.isEmpty()) {
                Text("还没有总结。", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.l))
            }
            state.summaries.forEach { local ->
                val s = local.value
                Column(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开") { open = s.id.toString() }.padding(vertical = Spacing.s),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(s.kind.label, style = type.caption.copy(color = if (s.kind == SummaryKind.Year) colors.accent else colors.muted))
                        Text(rangeText(s.rangeStart, s.rangeEnd), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted))
                    }
                    Text(s.body.lineSequence().map { it.trim().removePrefix("#").removePrefix("#").trim() }.filter { it.isNotEmpty() }.drop(if (s.body.trimStart().startsWith("#")) 1 else 0).firstOrNull().orEmpty(),
                        style = type.body.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (picking) {
        val range = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextAction("生成", {
                    val start = range.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    val end = range.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start
                    if (start != null && end != null) toast(vm.generate(SummaryKind.Custom, rangeText(start, end), start = start, end = end))
                    picking = false
                }, enabled = range.selectedStartDateMillis != null)
            },
            dismissButton = { TextAction("取消", { picking = false }, color = colors.muted) },
        ) {
            DateRangePicker(state = range, title = null, headline = null, showModeToggle = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryDetail(s: Summary, onBack: () -> Unit, onOpenSource: (SummarySource) -> Unit, onDelete: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var deleting by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar(s.kind.label, onBack) {
            if (!s.locked) TextAction("删除", { deleting = true }, color = colors.muted)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(rangeText(s.rangeStart, s.rangeEnd), style = type.numeral.copy(fontSize = 19.tsp, color = colors.muted), modifier = Modifier.semantics { heading() })
            Text(if (s.locked) "AI 派生 · 年度回顾，不可撤回" else "AI 派生，仅供参考", style = type.caption.copy(color = colors.accent))
            MarkdownView(s.body, 17.tsp, 1.9f, modifier = Modifier.padding(top = Spacing.m))
            if (s.sources.isNotEmpty()) {
                SectionLabel("来源", modifier = Modifier.padding(top = Spacing.l))
                s.sources.forEach { src ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开原来的记录") { onOpenSource(src) }.padding(vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Text("[${src.number}]", style = type.numeral.copy(fontSize = 15.tsp, color = colors.accent), modifier = Modifier.widthIn(min = 36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sourceKind(src.type), style = type.caption.copy(color = colors.faint))
                            Text(sourceLabel(src), style = type.body.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
    if (deleting) {
        ConfirmDialog("删除这份总结？", "会进回收站，可以恢复。", "删除", onConfirm = { deleting = false; onDelete() }, onDismiss = { deleting = false })
    }
}
