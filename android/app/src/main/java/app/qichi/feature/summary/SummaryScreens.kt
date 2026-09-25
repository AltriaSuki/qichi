package app.qichi.feature.summary

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.color
import app.qichi.core.designsystem.component.AiMark
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.RefChip
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.ThinkingDots
import app.qichi.core.designsystem.component.decor.Illustration
import app.qichi.core.designsystem.component.decor.Scene
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.decor.WaxSeal
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.MarkdownView
import app.qichi.core.ui.sourceKind
import app.qichi.core.ui.sourceLabel
import app.qichi.core.ui.sourceLook
import app.qichi.shared.api.Summary
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.SummaryKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
        FeatureTopBar(
            Feature.Summary, onBack,
            menu = listOf(
                MenuAction("写这一周的回顾", { toast(vm.generate(SummaryKind.Week, "这一周")) }),
                MenuAction("写这个月的回顾", { toast(vm.generate(SummaryKind.Month, "这个月")) }),
                MenuAction("写上个月的回顾", { toast(vm.generate(SummaryKind.Month, "上个月", anchor = state.today.minusMonths(1))) }),
                MenuAction("选一段时间……", { picking = true }),
            ),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = Spacing.xs)) {
            state.pending.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AiMark()
                    Text(if (p.failed) "「${p.label}」没有写成" else "正在写「${p.label}」的回顾……", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                    if (p.failed) {
                        TextAction("重试", p.retry)
                        TextAction("算了", { vm.dismiss(p.jobId) }, color = colors.muted)
                    } else {
                        ThinkingDots()
                    }
                }
            }
            if (state.loaded && state.summaries.isEmpty() && state.pending.isEmpty()) {
                Text("还没有总结。右上角「更多」里可以请 AI 回顾这一周、这个月，或者选一段时间。每句都标出依据，点得回去。",
                    style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
            }
            val groups = listOf(
                Triple("年度", QichiIcons.Spark, colors.accent) to state.summaries.filter { it.value.kind == SummaryKind.Year },
                Triple("每月", QichiIcons.Calendar, colors.personA) to state.summaries.filter { it.value.kind == SummaryKind.Month },
                Triple("每周", QichiIcons.Clock, colors.personB) to state.summaries.filter { it.value.kind == SummaryKind.Week },
                Triple("其它", QichiIcons.Summary, colors.muted) to state.summaries.filter { it.value.kind == SummaryKind.Custom },
            )
            var index = 0
            groups.forEach { (head, list) ->
                if (list.isEmpty()) return@forEach
                SectionLabel(head.first, Modifier.padding(top = Spacing.s), icon = head.second, tint = head.third)
                Column(Modifier.padding(top = 4.dp, bottom = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    list.forEach { local -> SummaryCard(local.value, state.today, index++) { open = local.value.id.toString() } }
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

private val MD = DateTimeFormatter.ofPattern("MM.dd")
private val chineseMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")

/** 总结的名字和封面上的大数字：年度「2025 年度回顾」/「25」、月「九月」/「09」、周「第 38 周」/「38」、其它写日期范围。 */
private fun summaryTitle(s: Summary): Pair<String, String> = when (s.kind) {
    SummaryKind.Year -> "${s.rangeStart.year} 年度回顾" to "%02d".format(s.rangeStart.year % 100)
    SummaryKind.Month -> chineseMonths[s.rangeStart.monthValue - 1] to "%02d".format(s.rangeStart.monthValue)
    SummaryKind.Week -> s.rangeStart.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR).let { "第 $it 周" to "$it" }
    SummaryKind.Custom -> rangeText(s.rangeStart, s.rangeEnd) to "%02d".format(s.rangeStart.monthValue)
}

private fun summaryScene(s: Summary): Scene = Scene.entries[Math.floorMod(s.id.hashCode(), Scene.entries.size)]

/** 一份总结是一张卡片：左边插画上压一个大数字，右边名字和日期范围；年度回顾右边一枚「年」字蜡封。 */
@Composable
private fun SummaryCard(s: Summary, today: java.time.LocalDate, index: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val (title, big) = summaryTitle(s)
    val rotation = listOf(-.4f, .5f, -.5f, .3f)[index % 4]
    Row(
        Modifier.rotate(rotation).fillMaxWidth().lift(colors).clip(QichiShapes.card).background(colors.card)
            .clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(72.dp)) {
            Illustration(summaryScene(s), Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
            Text(big, style = type.numeral.copy(fontSize = 26.tsp, fontWeight = FontWeight.W500, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = .35f), blurRadius = 6f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp).clearAndSetSemantics { })
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = type.headline.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (java.time.temporal.ChronoUnit.DAYS.between(s.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate(), today) <= 2) {
                    Sticker("新", color = colors.personA, rotation = -4f, fontSizeSp = 14f)
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.kind == SummaryKind.Year) {
                    AiMark(size = 12)
                    Text("派生 · 不可撤回", style = type.caption.copy(color = colors.muted))
                } else {
                    Text("${s.rangeStart.format(MD)} – ${s.rangeEnd.format(MD)}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                }
            }
        }
        if (s.kind == SummaryKind.Year) WaxSeal("年", size = 44.dp)
    }
}

@Composable
private fun SummaryDetail(s: Summary, onBack: () -> Unit, onOpenSource: (SummarySource) -> Unit, onDelete: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var deleting by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val (title, big) = summaryTitle(s)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ItemTopBar(title, onBack, feature = Feature.Summary,
            menu = if (!s.locked) listOf(MenuAction("删除", { deleting = true }, danger = true)) else emptyList())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.page, end = Spacing.page, top = 6.dp), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            // 封面：插画 + 大数字 + 印章 + 胶带
            Box(Modifier.fillMaxWidth()) {
                Illustration(summaryScene(s), Modifier.fillMaxWidth().height(128.dp), RoundedCornerShape(14.dp), description = title)
                Text(big, style = type.dateDisplay.copy(fontSize = 54.sp, lineHeight = 54.sp, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = .3f), blurRadius = 10f)),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 8.dp).clearAndSetSemantics { })
                Seal("栖迟", Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp), size = 34.dp, rotation = -8f)
                Tape(Modifier.align(Alignment.TopEnd).padding(end = 40.dp).offset(y = (-8).dp), color = colors.personB, width = 56.dp, rotation = 5f, alpha = .35f)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    AiMark()
                    Text(if (s.locked) "整理 · 年度回顾，不可撤回" else "整理，仅供参考", style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                    Text("${s.rangeStart.format(MD)} – ${s.rangeEnd.format(MD)}", style = type.numeral.copy(fontSize = 13.tsp, color = colors.muted), modifier = Modifier.semantics { heading() })
                }
                MarkdownView(s.body, 16.tsp, 1.85f, modifier = Modifier.padding(top = Spacing.s))
            }
            if (s.sources.isNotEmpty()) {
                Column {
                    SectionLabel("来源", icon = QichiIcons.Arrow, tint = colors.personB)
                    s.sources.forEachIndexed { i, src ->
                        val (icon, tone) = sourceLook(src.type)
                        Row(
                            Modifier.fillMaxWidth().then(if (i == 0) Modifier else Modifier.dashedDivider(colors, atTop = true))
                                .clickable(role = Role.Button, onClickLabel = "打开原来的记录") { onOpenSource(src) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            RefChip(src.number)
                            FeatureTile(icon, tone.color, size = 30.dp)
                            Column(Modifier.weight(1f)) {
                                Text(sourceKind(src.type), style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = tone.color))
                                Text(sourceLabel(src), style = type.preview.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(src.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(MD), style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }
    if (deleting) {
        ConfirmDialog("删除这份总结？", "会进回收站，可以恢复。", "删除", onConfirm = { deleting = false; onDelete() }, onDismiss = { deleting = false })
    }
}
