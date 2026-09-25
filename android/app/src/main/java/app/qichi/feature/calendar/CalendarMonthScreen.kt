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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.Person
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.dashedBorder
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Watermark
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
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
    onNewEvent: () -> Unit = onEventsClick,
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
    Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize()) {
        FeatureTopBar(
            Feature.Calendar, onBack,
            actions = listOf(BarAction("回到今天", QichiIcons.CalendarToday, vm::today)),
            menu = listOf(
                MenuAction("全部日程", onEventsClick),
                MenuAction("导入日历文件", { pickIcs.launch(arrayOf("text/calendar", "application/octet-stream", "*/*")) }),
                MenuAction("导出", transferVm::prepareExport),
                MenuAction("订阅链接", { transferVm.subscription() }),
            ),
        )
        Segmented(
            listOf("日", "周", "月"),
            selected = if (state.view == CalendarView.Month) 2 else 1,
            onSelect = { i ->
                when (i) {
                    0 -> onDayClick(state.selectedDate)
                    1 -> vm.show(CalendarView.Week)
                    else -> vm.show(CalendarView.Month)
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            MonthHeader(state, vm)
            Column(
                Modifier.padding(horizontal = Spacing.m).fillMaxWidth().lift(colors, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                    .background(colors.card).padding(start = 5.dp, end = 5.dp, top = 10.dp, bottom = 6.dp),
            ) {
                WeekdayHeader()
                if (state.view == CalendarView.Month) {
                    MonthGrid(state.monthStart, state.today, state.selectedDate, state.dayItems, state.people, vm::select)
                } else {
                    WeekGrid(state.selectedDate, state.today, state.dayItems, state.people, vm::select)
                }
            }
            DayAgenda(state, onDayClick)
            transfer.message?.let { message ->
                Text(message, style = QichiTheme.typography.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.xs))
            }
            Spacer(Modifier.height(FabClearance))
        }
    }
        Fab("新安排", onNewEvent)
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

/** 月份标题：大号淡水印数字 + 「九月」+ 年份 + 这个月接下来的一件事（贴纸），右边翻月。 */
@Composable
private fun MonthHeader(state: CalendarMonthState, vm: CalendarMonthViewModel) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val month = if (state.view == CalendarView.Month) state.monthStart else state.selectedDate
    val unit = if (state.view == CalendarView.Month) "个月" else "周"
    Box(Modifier.fillMaxWidth().padding(start = Spacing.page, end = Spacing.sm, bottom = 2.dp)) {
        // 水印数字不占位置：只画出来，不把下面的月历往下推
        Watermark(
            "%02d".format(month.monthValue),
            Modifier.align(Alignment.TopEnd).padding(end = 88.dp).layout { m, c ->
                val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(p.width, 0) { p.place(0, (-34).dp.roundToPx()) }
            },
            fontSizeSp = 110f, alpha = .06f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthNames[month.monthValue - 1], style = type.headline.copy(fontSize = 20.tsp, color = colors.ink), modifier = Modifier.semantics { heading() })
            Text(month.year.toString(), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted), modifier = Modifier.padding(start = Spacing.xs))
            state.upcomingInMonth?.let { (day, title) ->
                Sticker("$day 号$title", Modifier.padding(start = Spacing.s), color = colors.personA, rotation = -4f, fontSizeSp = 15f)
            }
            Spacer(Modifier.weight(1f))
            IconAction(QichiIcons.ChevronLeft, "上一$unit", { vm.move(-1) })
            IconAction(QichiIcons.ChevronRight, "下一$unit", { vm.move(1) })
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val colors = QichiTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(
                label,
                style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, fontWeight = FontWeight.W600, color = if (label == "六" || label == "日") colors.accent else colors.muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    items: Map<LocalDate, CalendarDayItems>,
    people: People,
    onSelect: (LocalDate) -> Unit,
) {
    val offset = month.dayOfWeek.value - 1
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(rows) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val number = week * 7 + column - offset + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (number in 1..month.lengthOfMonth()) {
                            val date = month.withDayOfMonth(number)
                            DateCell(date, today, selected, items[date], people) { onSelect(date) }
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
    people: People,
    onSelect: (LocalDate) -> Unit,
) {
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth()) {
        repeat(7) { index ->
            val date = monday.plusDays(index.toLong())
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DateCell(date, today, selected, items[date], people) { onSelect(date) }
            }
        }
    }
}

/**
 * 一天：等宽数字；今天是深色圆 + 淡玫瑰光晕，选中的（不是今天）是玫瑰色虚线圈。
 * 下面的小点：日程按参与的人着色（两个人的就两个点），计划目标和里程碑是雾蓝小菱形，截止的待办是空心点。
 */
@Composable
private fun DateCell(date: LocalDate, today: LocalDate, selected: LocalDate, items: CalendarDayItems?, people: People, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val isToday = date == today
    val isSelected = date == selected && !isToday
    Column(
        Modifier.height(46.dp).fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${date.monthValue}月${date.dayOfMonth}日" + if (items?.hasContent == true) "，有安排" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(32.dp)
                .then(
                    if (isToday) {
                        val halo = colors.personA.copy(alpha = .2f)
                        val ink = colors.ink
                        Modifier.drawBehind {
                            drawCircle(halo, radius = size.minDimension / 2 + 4.dp.toPx())
                            drawCircle(ink)
                        }
                    } else Modifier,
                )
                .then(if (isSelected) Modifier.dashedBorder(colors.personA, 16.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = QichiTheme.typography.numeral.copy(
                    fontSize = 15.tsp,
                    fontWeight = if (isToday) FontWeight.W500 else FontWeight.W400,
                    color = if (isToday) colors.background else colors.ink,
                ),
            )
        }
        Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            val dots = items?.let { dayDots(it, people) }.orEmpty()
            dots.forEach { dot ->
                when (dot) {
                    DayDot.A -> Box(Modifier.size(5.dp).background(colors.personA, CircleShape))
                    DayDot.B -> Box(Modifier.size(5.dp).background(colors.personB, CircleShape))
                    DayDot.Todo -> Box(Modifier.size(5.dp).border(1.dp, colors.muted, CircleShape))
                    DayDot.Milestone -> Box(Modifier.size(5.dp).rotate(45f).background(colors.personB))
                }
            }
        }
    }
}

/** 日历格子下面的小点：最多三个。 */
internal enum class DayDot { A, B, Todo, Milestone }

internal fun dayDots(items: CalendarDayItems, people: People): List<DayDot> {
    val out = linkedSetOf<DayDot>()
    items.events.forEach { e ->
        val ids = e.value.participantIds
        val whoA = people.members.filter { people.person(it.userId) == Person.A }.map { it.userId }.toSet()
        if (ids.isEmpty() || ids.size > 1) { out += DayDot.A; out += DayDot.B }
        else if (ids.single() in whoA) out += DayDot.A else out += DayDot.B
    }
    if (items.plans.isNotEmpty() || items.milestones.isNotEmpty()) out += DayDot.Milestone
    if (items.dueTodos.isNotEmpty()) out += DayDot.Todo
    return out.take(3)
}

/** 选中那天（默认今天）的安排：时间 / 标题 / 谁；已经过去的灰字，接下来那件浮起。 */
@Composable
private fun DayAgenda(state: CalendarMonthState, onDayClick: (LocalDate) -> Unit) {
    val colors = QichiTheme.colors
    val date = state.selectedDate
    val selected = state.selectedItems
    Column(Modifier.padding(start = Spacing.page, end = Spacing.page, top = Spacing.m)) {
        SectionLabel(
            if (date == state.today) "今天" else "${date.monthValue}月${date.dayOfMonth}日 ${date.dayOfWeek.chinese}",
            icon = QichiIcons.Sun, tint = colors.personA,
        ) { Text(date.format(MD), style = QichiTheme.typography.numeral.copy(fontSize = 12.tsp, color = colors.muted)) }
        if (selected.hasContent) {
            CalendarAgenda(selected, state.people, state.zone)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction("查看全天", { onDayClick(date) }, color = colors.muted)
            }
        } else {
            Text("这天没有安排。", style = QichiTheme.typography.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.xs))
        }
    }
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")

/** 月、日视图共用的当天内容：已经过去的日程灰字，接下来的第一件浮起衬淡玫瑰。 */
@Composable
internal fun CalendarAgenda(items: CalendarDayItems, people: People, zone: java.time.ZoneId) {
    val now = remember(items.date) { java.time.Instant.now() }
    val next = items.events.firstOrNull { e -> !e.value.allDay && (e.value.endsAt ?: e.value.startsAt)?.isAfter(now) == true }
    items.events.forEach { local ->
        val event = local.value
        val label = if (event.allDay) "全天" else event.startsAt?.atZone(zone)?.format(timeFormat).orEmpty()
        val owners = if (event.participantIds.isEmpty()) listOfNotNull(people.me?.userId, people.partner?.userId)
            else event.participantIds
        val past = !event.allDay && (event.endsAt ?: event.startsAt)?.isBefore(now) == true
        AgendaRow(label, event.title, owners, people, past = past, highlight = local == next)
    }
    items.dueTodos.forEach { local ->
        AgendaRow("截止", local.value.title,
            local.value.assigneeId?.let(::listOf) ?: listOfNotNull(people.me?.userId, people.partner?.userId), people, past = local.value.doneAt != null)
    }
    items.plans.forEach { local ->
        AgendaRow("目标", local.value.title, listOf(local.value.ownerId), people)
    }
    items.milestones.forEach { local ->
        AgendaRow("里程碑", local.value.title, emptyList(), people)
    }
}

@Composable
private fun AgendaRow(label: String, title: String, owners: List<UUID>, people: People, past: Boolean = false, highlight: Boolean = false) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = RoundedCornerShape(12.dp)
    val ink = if (past) colors.muted else colors.ink
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .then(if (highlight) Modifier.lift(colors, shape).clip(shape).background(colors.card).background(colors.personA.copy(alpha = .1f)) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = if (highlight) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width((58 * type.scale).dp)) {
            if (label.firstOrNull()?.isDigit() == true) {
                Text(label, style = type.numeral.copy(fontSize = 15.tsp, color = ink))
            } else {
                Text(label, style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = colors.accent))
            }
        }
        Text(title, style = type.bodyLarge.copy(color = ink), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (owners.size) {
            0 -> Unit
            1 -> PersonMark(people.markChar(owners[0]), people.person(owners[0]), size = 18.dp)
            else -> PersonMarks(owners.take(2).map { people.markChar(it) to people.person(it) }, size = 18.dp)
        }
    }
}
