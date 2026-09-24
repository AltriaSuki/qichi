package app.qichi.feature.calendar

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.chinese
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val monthNames = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** 日历入口：日视图在详情页，周和月在这里切换。 */
@Composable
fun CalendarMonthScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onEventsClick: () -> Unit,
    vm: CalendarMonthViewModel = hiltViewModel<CalendarMonthViewModel, CalendarMonthViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
    transferVm: CalendarTransferViewModel = hiltViewModel<CalendarTransferViewModel, CalendarTransferViewModel.Factory>(key = "transfer-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val transfer by transferVm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val context = LocalContext.current
    val pickIcs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(transferVm::importIcs)
    }
    val saveIcs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        transferVm.exportTo(uri)
    }
    var confirmingReset by remember { mutableStateOf(false) }
    LaunchedEffect(transfer.exportReady) {
        if (transfer.exportReady) {
            transferVm.clearExportReady()
            saveIcs.launch("栖迟日历.ics")
        }
    }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        FeatureTopBar(Feature.Calendar, onBack, actions = listOf(BarAction("全部日程", QichiIcons.BulletList, onEventsClick)))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            CalendarTab("日", false) { onDayClick(state.selectedDate) }
            CalendarTab("周", state.view == CalendarView.Week) { vm.show(CalendarView.Week) }
            CalendarTab("月", state.view == CalendarView.Month) { vm.show(CalendarView.Month) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.page, end = Spacing.s, top = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weekStart = state.selectedDate.minusDays((state.selectedDate.dayOfWeek.value - 1).toLong())
                val heading = if (state.view == CalendarView.Month) monthNames[state.monthStart.monthValue - 1]
                    else "${monthNames[weekStart.monthValue - 1]} · ${weekStart.dayOfMonth}日"
                Text(
                    heading, maxLines = 1,
                    style = QichiTheme.typography.hubTitle.copy(
                        fontSize = if (state.view == CalendarView.Month) 40.tsp else 27.tsp,
                        letterSpacing = if (state.view == CalendarView.Month) 0.24.em else 0.08.em,
                        color = colors.ink,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(state.selectedDate.year.toString(), style = QichiTheme.typography.numeral.copy(fontSize = 22.tsp, color = colors.muted))
                IconAction(QichiIcons.ChevronLeft, "上一${if (state.view == CalendarView.Month) "个月" else "周"}", { vm.move(-1) })
                IconAction(QichiIcons.ChevronRight, "下一${if (state.view == CalendarView.Month) "个月" else "周"}", { vm.move(1) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page), horizontalArrangement = Arrangement.End) {
                TextAction("今天", vm::today, color = colors.muted)
            }
            if (state.view == CalendarView.Month) {
                MonthGrid(state.monthStart, state.today, state.selectedDate, state.dayItems, vm::select)
            } else {
                WeekGrid(state.selectedDate, state.today, state.dayItems, vm::select)
            }
            val selected = state.selectedItems
            if (selected.hasContent) {
                MistCard(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.l)) {
                    SectionLabel("${state.selectedDate.dayOfWeek.chinese} · ${state.selectedDate.dayOfMonth}")
                    CalendarAgenda(selected, state.people, state.zone)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextAction("查看全天", { onDayClick(state.selectedDate) }, color = colors.muted)
                    }
                }
            }
        }
        transfer.message?.let { message ->
            Text(message, style = QichiTheme.typography.caption.copy(color = colors.muted),
                modifier = Modifier.padding(horizontal = Spacing.page))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextAction("导入", { pickIcs.launch(arrayOf("text/calendar", "application/octet-stream", "*/*")) }, color = colors.muted)
            Text(" · ", style = QichiTheme.typography.caption.copy(color = colors.faint))
            TextAction("导出", transferVm::prepareExport, color = colors.muted)
            Text(" · ", style = QichiTheme.typography.caption.copy(color = colors.faint))
            TextAction("订阅", { transferVm.subscription() }, color = colors.muted)
        }
    }
    transfer.subscriptionUrl?.let { url ->
        AlertDialog(
            onDismissRequest = transferVm::closeSubscription,
            title = { Text("只读日历订阅", style = QichiTheme.typography.pageTitle) },
            text = {
                Column {
                    Text("把链接添加到日历应用，就能订阅两个人的日程。拿到链接的人也能查看，分享时请留意。",
                        style = QichiTheme.typography.bodyLarge)
                    TextAction("重置链接", { confirmingReset = true }, color = colors.accent)
                }
            },
            confirmButton = {
                TextAction("分享链接", {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    context.startActivity(Intent.createChooser(intent, "分享日历订阅链接"))
                }, color = colors.accent)
            },
            dismissButton = { TextAction("关闭", transferVm::closeSubscription, color = colors.muted) },
        )
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("重置订阅链接？", style = QichiTheme.typography.pageTitle) },
            text = { Text("旧链接会立即失效，已经订阅的日历需要重新添加。", style = QichiTheme.typography.bodyLarge) },
            confirmButton = { TextAction("重置", { confirmingReset = false; transferVm.subscription(reset = true) }, color = colors.accent) },
            dismissButton = { TextAction("取消", { confirmingReset = false }, color = colors.muted) },
        )
    }
}

@Composable
private fun CalendarTab(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    Box(
        Modifier.heightIn(min = Sizes.listRow).clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = active },
        contentAlignment = Alignment.Center,
    ) {
        if (active) Box(Modifier.align(Alignment.TopCenter).size(Spacing.xxs).clip(CircleShape).background(colors.accent))
        Text(label, style = QichiTheme.typography.tab.copy(
            color = if (active) colors.ink else colors.muted,
            fontWeight = if (active) FontWeight.W400 else FontWeight.W300,
        ))
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    items: Map<LocalDate, CalendarDayItems>,
    onSelect: (LocalDate) -> Unit,
) {
    val offset = month.dayOfWeek.value - 1
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    Column(Modifier.padding(horizontal = Spacing.l)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(Modifier.weight(1f).heightIn(min = Sizes.touchTarget), contentAlignment = Alignment.Center) {
                    Text(label, style = QichiTheme.typography.sectionLabel.copy(color = QichiTheme.colors.muted))
                }
            }
        }
        repeat(rows) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val number = week * 7 + column - offset + 1
                    Box(Modifier.weight(1f).height(Sizes.calendarCellHeight), contentAlignment = Alignment.Center) {
                        if (number in 1..month.lengthOfMonth()) {
                            val date = month.withDayOfMonth(number)
                            DateCell(date, today, selected, items[date], { onSelect(date) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekGrid(
    selected: LocalDate,
    today: LocalDate,
    items: Map<LocalDate, CalendarDayItems>,
    onSelect: (LocalDate) -> Unit,
) {
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        repeat(7) { index ->
            val date = monday.plusDays(index.toLong())
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfWeek.chinese.takeLast(1), style = QichiTheme.typography.sectionLabel.copy(color = QichiTheme.colors.muted))
                DateCell(date, today, selected, items[date], { onSelect(date) })
            }
        }
    }
}

@Composable
private fun DateCell(date: LocalDate, today: LocalDate, selected: LocalDate, items: CalendarDayItems?, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    Column(
        Modifier.height(Sizes.calendarCellHeight).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Box(
            Modifier.size(Sizes.calendarDate).clip(CircleShape)
                .background(if (date == selected) colors.personA else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(date.dayOfMonth.toString(), style = QichiTheme.typography.numeral.copy(
                fontSize = 21.tsp,
                color = if (date == selected) colors.onPerson else if (date == today) colors.ink else colors.faint,
            ))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            if (items?.events?.isNotEmpty() == true) CalendarDot(colors.personA)
            if (items?.dueTodos?.isNotEmpty() == true) CalendarDot(colors.ink, hollow = true)
            if (items?.plans?.isNotEmpty() == true || items?.milestones?.isNotEmpty() == true) CalendarDot(colors.personB)
        }
    }
}

@Composable
private fun CalendarDot(color: Color, hollow: Boolean = false) {
    Box(Modifier.size(Sizes.calendarDot).clip(CircleShape)
        .background(if (hollow) QichiTheme.colors.background else color)
        .then(if (hollow) Modifier.border(1.dp, color, CircleShape) else Modifier))
}

/** 月、日视图共用的当天内容。 */
@Composable
internal fun CalendarAgenda(items: CalendarDayItems, people: People, zone: java.time.ZoneId) {
    items.events.forEach { local ->
        val event = local.value
        val label = if (event.allDay) "全天" else event.startsAt?.atZone(zone)?.format(timeFormat).orEmpty()
        val owners = if (event.participantIds.isEmpty()) listOfNotNull(people.me?.userId, people.partner?.userId)
            else event.participantIds
        AgendaRow(label, event.title, QichiTheme.colors.personA, owners, people)
    }
    items.dueTodos.forEach { local ->
        AgendaRow("截止", local.value.title, QichiTheme.colors.ink,
            local.value.assigneeId?.let(::listOf) ?: listOfNotNull(people.me?.userId, people.partner?.userId), people)
    }
    items.plans.forEach { local ->
        AgendaRow("目标", local.value.title, QichiTheme.colors.accent, listOf(local.value.ownerId), people)
    }
    items.milestones.forEach { local ->
        AgendaRow("里程碑", local.value.title, QichiTheme.colors.personB, emptyList(), people)
    }
}

@Composable
private fun AgendaRow(label: String, title: String, color: Color, owners: List<UUID>, people: People) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRowTall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(Modifier.size(Sizes.calendarDot).clip(CircleShape).background(color))
        Text(label, style = if (label.firstOrNull()?.isDigit() == true)
            QichiTheme.typography.numeral.copy(fontSize = 22.tsp, color = QichiTheme.colors.muted)
            else QichiTheme.typography.caption.copy(color = QichiTheme.colors.muted),
            modifier = Modifier.width(Sizes.calendarLabelWidth))
        Text(title, style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (owners.size) {
            0 -> Unit
            1 -> PersonMark(people.markChar(owners[0]), people.person(owners[0]), size = 18.dp)
            else -> PersonMarks(owners.take(2).map { people.markChar(it) to people.person(it) })
        }
    }
}
