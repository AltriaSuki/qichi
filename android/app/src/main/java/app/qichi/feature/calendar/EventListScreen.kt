package app.qichi.feature.calendar

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.EventDraft
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.sync.Local
import app.qichi.core.ui.chinese
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Event
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/** 日程（列表版）：从今天起按天列出，右上角新建，点一条编辑。月视图在 P4-07。 */
@Composable
fun EventListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: EventListViewModel = hiltViewModel<EventListViewModel, EventListViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        BackBar(title = "日历", onBack = onBack) {
            IconAction(QichiIcons.Plus, contentDescription = "新日程", onClick = { editing = "new" })
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = Spacing.page),
        ) {
            items(state.days, key = { it.date.toString() }) { day ->
                Column(Modifier.padding(bottom = Spacing.l)) {
                    val (label, _) = relativeDay(day.date, state.today)
                    SectionLabel(if (label.startsWith("周") || label == "今天" || label == "明天" || label == "昨天") label else day.date.dayOfWeek.chinese) {
                        Text("${day.date.monthValue} · ${day.date.dayOfMonth}", style = type.numeral.copy(fontSize = 15.sp, color = colors.muted))
                    }
                    day.events.forEach { item ->
                        EventRow(item, state.people, state.zone, day.date) { editing = item.value.id.toString() }
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.xxl)) }
        }
    }

    editing?.let { key ->
        val existing = if (key == "new") null else state.all.firstOrNull { it.value.id.toString() == key }?.value
        if (key != "new" && existing == null) {
            editing = null
            return@let
        }
        EventEditor(
            existing = existing,
            people = state.people,
            zone = state.zone,
            today = state.today,
            onDismiss = { editing = null },
            onSave = { draft -> viewModel.save(existing, draft); editing = null },
            onDelete = { existing?.let(viewModel::delete); editing = null },
        )
    }
}

@Composable
private fun EventRow(item: Local<Event>, people: People, zone: ZoneId, day: LocalDate, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val e = item.value
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowTall)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val time = when {
            e.allDay -> "全天"
            else -> {
                val start = e.startsAt!!.atZone(zone)
                // 跨天的日程：第一天写开始时间，之后的日子写「续」
                if (start.toLocalDate() == day) start.format(hm) else "续"
            }
        }
        Text(
            time,
            style = if (e.allDay || time == "续") type.caption.copy(color = colors.muted) else type.numeral.copy(fontSize = 18.sp, color = colors.ink),
            modifier = Modifier.width(52.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(e.title, style = type.bodyLarge.copy(color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (!e.allDay) "至 ${e.endsAt!!.atZone(zone).format(hm)}" else null,
                e.location,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = type.caption.copy(color = colors.muted))
        }
        if (item.isPending) Icon(QichiIcons.Clock, contentDescription = "待发送", tint = colors.muted, modifier = Modifier.size(13.dp))
        if (e.participantIds.size == 1) {
            val p = e.participantIds.single()
            PersonMark(people.markChar(p), people.person(p), size = 18.dp)
        } else {
            val both = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
            if (both.size == 2) PersonMarks(both)
        }
    }
}

private enum class Who { Both, Me, Partner }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(
    existing: Event?,
    people: People,
    zone: ZoneId,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember {
        mutableStateOf(existing?.let { if (it.allDay) it.startDate else it.startsAt?.atZone(zone)?.toLocalDate() } ?: today)
    }
    var endDate by remember {
        mutableStateOf(existing?.let { if (it.allDay) it.endDate else it.endsAt?.atZone(zone)?.toLocalDate() } ?: today)
    }
    var startTime by remember { mutableStateOf(existing?.startsAt?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(19, 0)) }
    var endTime by remember { mutableStateOf(existing?.endsAt?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(20, 0)) }
    var location by remember { mutableStateOf(existing?.location.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var who by remember {
        mutableStateOf(
            when {
                existing == null || existing.participantIds.size != 1 -> Who.Both
                existing.participantIds.single() == people.myUserId -> Who.Me
                else -> Who.Partner
            },
        )
    }
    var picking by remember { mutableStateOf<String?>(null) }

    val startsAt = startDate.atTime(startTime).atZone(zone).toInstant()
    val endsAt = endDate.atTime(endTime).atZone(zone).toInstant()
    val valid = title.isNotBlank() && if (allDay) !endDate.isBefore(startDate) else !endsAt.isBefore(startsAt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            QichiTextField(value = title, onValueChange = { title = it }, label = if (existing == null) "新日程" else "日程", placeholder = "做什么")
            Row(
                Modifier
                    .heightIn(min = 46.dp)
                    .toggleable(value = allDay, role = Role.Checkbox) { allDay = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckCircle(checked = allDay, onCheckedChange = null)
                Text("全天", style = type.bodyLarge.copy(color = colors.ink))
            }
            Column {
                SectionLabel("开始")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    DateTimeChip(dateLabel(startDate, today), numeral = false) { picking = "startDate" }
                    if (!allDay) DateTimeChip(startTime.format(hm), numeral = true) { picking = "startTime" }
                }
            }
            Column {
                SectionLabel("结束")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    DateTimeChip(dateLabel(endDate, today), numeral = false) { picking = "endDate" }
                    if (!allDay) DateTimeChip(endTime.format(hm), numeral = true) { picking = "endTime" }
                }
                if (!valid && title.isNotBlank()) Text("结束不能早于开始", style = type.caption.copy(color = colors.accent))
            }
            Column {
                SectionLabel("谁")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChoicePill("两个人", who == Who.Both, { who = Who.Both }, Modifier.weight(1f))
                    ChoicePill(people.me?.displayName ?: "我", who == Who.Me, { who = Who.Me }, Modifier.weight(1f))
                    people.partner?.let { ChoicePill(it.displayName, who == Who.Partner, { who = Who.Partner }, Modifier.weight(1f)) }
                }
            }
            QichiTextField(value = location, onValueChange = { location = it }, label = "地点")
            QichiTextField(value = note, onValueChange = { note = it }, label = "备注", singleLine = false)
            PrimaryButton("保存", enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
                val participants = when (who) {
                    Who.Both -> emptyList()
                    Who.Me -> listOfNotNull(people.myUserId)
                    Who.Partner -> listOfNotNull(people.partner?.userId)
                }
                onSave(
                    if (allDay) {
                        EventDraft(title, true, startDate = startDate, endDate = endDate, location = location, note = note, participantIds = participants)
                    } else {
                        EventDraft(title, false, startsAt = startsAt, endsAt = endsAt, location = location, note = note, participantIds = participants)
                    },
                )
            })
            if (existing != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TextAction("删除", onClick = onDelete) }
            }
            Spacer(Modifier.height(Spacing.s))
        }
    }

    when (picking) {
        "startDate", "endDate" -> {
            val current = if (picking == "startDate") startDate else endDate
            val dateState = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextAction("确定", onClick = {
                        dateState.selectedDateMillis?.let {
                            val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            if (picking == "startDate") {
                                val span = endDate.toEpochDay() - startDate.toEpochDay()
                                startDate = d
                                endDate = d.plusDays(maxOf(0, span))
                            } else {
                                endDate = d
                            }
                        }
                        picking = null
                    })
                },
                dismissButton = { TextAction("取消", onClick = { picking = null }, color = colors.muted) },
            ) { DatePicker(state = dateState, title = null, headline = null, showModeToggle = false) }
        }
        "startTime", "endTime" -> {
            val current = if (picking == "startTime") startTime else endTime
            val timeState = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = null },
                containerColor = colors.paper,
                confirmButton = {
                    TextAction("确定", onClick = {
                        val t = LocalTime.of(timeState.hour, timeState.minute)
                        if (picking == "startTime") {
                            val length = java.time.Duration.between(startTime, endTime)
                            startTime = t
                            if (!length.isNegative) endTime = t.plus(length)
                        } else {
                            endTime = t
                        }
                        picking = null
                    })
                },
                dismissButton = { TextAction("取消", onClick = { picking = null }, color = colors.muted) },
                text = { TimePicker(state = timeState) },
            )
        }
    }
}

private fun dateLabel(date: LocalDate, today: LocalDate): String {
    val (label, numeral) = relativeDay(date, today)
    return if (numeral) label else "$label ${date.monthValue} · ${date.dayOfMonth}"
}

@Composable
private fun DateTimeChip(text: String, numeral: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .heightIn(min = Sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = if (numeral) type.numeral.copy(fontSize = 22.sp, color = colors.ink) else type.bodyLarge.copy(color = colors.ink))
    }
}
