package app.qichi.feature.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.relativeDay
import app.qichi.shared.rules.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 待办页：进行中（按截止日）与已完成；点一条打开编辑，右下角新建。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: TodoViewModel = hiltViewModel<TodoViewModel, TodoViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    // null = 不显示；"new" = 新建；其它 = 编辑这条
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Todo, onBack)
            val sections = state.sections
            var showDone by rememberSaveable { mutableStateOf(false) }
            val edit: (TodoGroup) -> Unit = { editing = it.todo.value.id.toString() }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.page),
            ) {
                section("today", "今天", QichiIcons.Sun, sections.today, state, viewModel, edit, showDue = false) {
                    if (sections.today.isNotEmpty()) Sticker("还有 ${sections.today.size} 件", color = QichiTheme.colors.personB, rotation = -3f, fontSizeSp = 15f)
                }
                section("week", "这周", QichiIcons.Calendar, sections.week, state, viewModel, edit, showDue = true) {
                    Text("${sections.week.size}", style = QichiTheme.typography.numeral.copy(fontSize = 13.tsp, color = QichiTheme.colors.muted))
                }
                section("later", "以后", QichiIcons.Clock, sections.later, state, viewModel, edit, showDue = true) {}
                if (state.done.isNotEmpty()) {
                    item(key = "done-toggle") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = Spacing.m).dashedDivider(colors, atTop = true).heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = if (showDone) "收起已完成" else "展开已完成") { showDone = !showDone },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(QichiIcons.Check, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                            Text("已完成", style = QichiTheme.typography.body.copy(fontWeight = FontWeight.W500, color = colors.muted))
                            Text("${state.done.size}", style = QichiTheme.typography.numeral.copy(fontSize = 14.tsp, color = colors.muted))
                            Spacer(Modifier.weight(1f))
                            Icon(if (showDone) QichiIcons.Down else QichiIcons.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (showDone) {
                        items(state.done, key = { "done-" + it.todo.value.id }) { group ->
                            TodoRow(
                                item = group.todo, people = state.people, today = state.today, zone = state.zone,
                                onToggle = { viewModel.toggle(group.todo.value, it) },
                                onClick = { edit(group) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(FabClearance)) }
            }
        }
        Fab("新待办", { editing = "new" })
    }

    editing?.let { key ->
        val group = if (key == "new") null else state.find(UUID.fromString(key))
        if (key != "new" && group == null) {
            editing = null
            return@let
        }
        TodoEditor(
            group = group,
            people = state.people,
            today = state.today,
            plans = state.plans,
            initial = group?.let { TodoForm.of(it.todo.value, state.people, state.zone) } ?: TodoForm(),
            onDismiss = { editing = null },
            onSave = { form ->
                if (group == null) viewModel.create(form) else viewModel.update(group.todo.value, form)
                editing = null
            },
            onDelete = { group?.let(viewModel::delete); editing = null },
            onAddSubtask = { title -> group?.let { viewModel.addSubtask(it.todo.value, title) } },
            onToggleChild = { child, done -> viewModel.toggle(child, done) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TodoEditor(
    group: TodoGroup?,
    people: People,
    today: LocalDate,
    plans: List<app.qichi.shared.api.Plan>,
    initial: TodoForm,
    onDismiss: () -> Unit,
    onSave: (TodoForm) -> Unit,
    onDelete: () -> Unit,
    onAddSubtask: (String) -> Unit,
    onToggleChild: (app.qichi.shared.api.Todo, Boolean) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var form by remember(group?.todo?.value?.id) { mutableStateOf(initial) }
    var pickingDate by remember { mutableStateOf(false) }
    var subtask by remember { mutableStateOf("") }

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
            QichiTextField(
                value = form.title, onValueChange = { form = form.copy(title = it) },
                label = if (group == null) "新待办" else "待办", placeholder = "要做什么",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Column {
                SectionLabel("交给")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChoicePill("两个人", form.assignee == Assignee.Both, { form = form.copy(assignee = Assignee.Both) }, Modifier.weight(1f))
                    ChoicePill(people.me?.displayName ?: "我", form.assignee == Assignee.Me, { form = form.copy(assignee = Assignee.Me) }, Modifier.weight(1f))
                    if (people.partner != null) {
                        ChoicePill(people.partner!!.displayName, form.assignee == Assignee.Partner, { form = form.copy(assignee = Assignee.Partner) }, Modifier.weight(1f))
                    }
                }
            }
            Column {
                SectionLabel("截止") {
                    form.dueDate?.let { Text(relativeDay(it, today).first, style = type.caption.copy(color = colors.accent)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChoicePill("不设", form.dueDate == null, { form = form.copy(dueDate = null, repeat = Repeat.None) }, Modifier.weight(1f))
                    ChoicePill("今天", form.dueDate == today, { form = form.copy(dueDate = today) }, Modifier.weight(1f))
                    ChoicePill("明天", form.dueDate == today.plusDays(1), { form = form.copy(dueDate = today.plusDays(1)) }, Modifier.weight(1f))
                    val other = form.dueDate != null && form.dueDate != today && form.dueDate != today.plusDays(1)
                    ChoicePill("选日期", other, { pickingDate = true }, Modifier.weight(1f))
                }
            }
            if (group?.todo?.value?.parentId == null) {
                Column {
                    SectionLabel("重复")
                    FlowRow(maxItemsInEachRow = 4, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Repeat.entries.forEach { r ->
                            ChoicePill(r.label, form.repeat == r, {
                                form = form.copy(repeat = r, dueDate = if (r != Repeat.None && form.dueDate == null) today else form.dueDate)
                            }, Modifier.weight(1f))
                        }
                    }
                }
            }
            // 子任务跟着父待办，不单独挂计划；没有计划时不显示
            if (group?.todo?.value?.parentId == null && (plans.isNotEmpty() || form.planId != null)) {
                Column {
                    SectionLabel("计划")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChoicePill("不属于", form.planId == null, { form = form.copy(planId = null) })
                        plans.forEach { plan ->
                            ChoicePill(plan.title, form.planId == plan.id, { form = form.copy(planId = plan.id) })
                        }
                    }
                }
            }
            QichiTextField(value = form.note, onValueChange = { form = form.copy(note = it) }, label = "备注", singleLine = false)

            if (group != null && group.todo.value.parentId == null) {
                Column {
                    SectionLabel("子任务")
                    group.children.forEach { child ->
                        TodoRow(
                            item = child, people = people, today = today, zone = java.time.ZoneId.systemDefault(), subtask = true,
                            onToggle = { onToggleChild(child.value, it) }, onClick = {},
                        )
                    }
                    QichiTextField(
                        value = subtask, onValueChange = { subtask = it }, label = "加一个子任务",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onAddSubtask(subtask); subtask = "" }),
                    )
                }
            }

            PrimaryButton("保存", onClick = { onSave(form) }, enabled = form.canSave, modifier = Modifier.fillMaxWidth())
            if (group != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextAction("删除", onClick = onDelete)
                }
            }
            Spacer(Modifier.height(Spacing.s))
        }
    }

    if (pickingDate) {
        val initialMillis = (form.dueDate ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextAction("确定", onClick = {
                    dateState.selectedDateMillis?.let { form = form.copy(dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickingDate = false
                })
            },
            dismissButton = { TextAction("取消", onClick = { pickingDate = false }, color = colors.muted) },
        ) {
            DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
        }
    }
}

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val MD = DateTimeFormatter.ofPattern("MM.dd")

/** 一段：小标题 + 这一段的待办（子任务缩进在下面）。空的段不显示。 */
private fun LazyListScope.section(
    key: String,
    title: String,
    icon: ImageVector,
    groups: List<TodoGroup>,
    state: TodoUiState,
    viewModel: TodoViewModel,
    onEdit: (TodoGroup) -> Unit,
    showDue: Boolean,
    trailing: @Composable RowScope.() -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "label-$key") {
        SectionLabel(title, Modifier.padding(top = if (key == "today") 0.dp else Spacing.ml), icon = icon, tint = QichiTheme.colors.personB, trailing = trailing)
    }
    items(groups, key = { it.todo.value.id }) { group ->
        Column {
            TodoRow(
                item = group.todo, people = state.people, today = state.today, zone = state.zone,
                onToggle = { viewModel.toggle(group.todo.value, it) },
                onClick = { onEdit(group) },
                meta = todoMeta(group, state, showDue),
            )
            group.children.forEach { child ->
                TodoRow(
                    item = child, people = state.people, today = state.today, zone = state.zone, subtask = true,
                    onToggle = { viewModel.toggle(child.value, it) },
                    onClick = { onEdit(group) },
                )
            }
        }
    }
}

/** 标题下的小字：截止（这周写星期、以后写日期）· ⚑ 计划 · 子任务进度 · 重复。什么都没有时为空。 */
private fun todoMeta(group: TodoGroup, state: TodoUiState, showDue: Boolean): (@Composable () -> Unit)? {
    val todo = group.todo.value
    val due = todo.dueDate ?: todo.dueAt?.atZone(state.zone)?.toLocalDate()
    val dueText = if (showDue && due != null) {
        if (!due.isAfter(state.today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY)))) weekdays[due.dayOfWeek.value - 1] else null
    } else null
    val dueDigits = if (showDue && due != null && dueText == null) due.format(MD) else null
    val plan = todo.planId?.let { state.planTitles[it] }
    val progress = group.children.takeIf { it.isNotEmpty() }?.let { c -> "${c.count { it.value.doneAt != null }}/${c.size}" }
    val repeat = todo.recurrence?.let(Recurrence::parse)?.let { r ->
        when (r.freq) {
            Recurrence.Freq.DAILY -> "每天"
            Recurrence.Freq.WEEKLY -> "每周"
            Recurrence.Freq.MONTHLY -> "每月"
        }
    }
    // 每一段：文字、是否等宽（数字）、前面的小图标
    val parts = listOfNotNull(
        dueText?.let { Triple(it, false, null) },
        dueDigits?.let { Triple(it, true, null) },
        plan?.let { Triple(it, false, QichiIcons.Flag) },
        progress?.let { Triple(it, true, null) },
        repeat?.let { Triple(it, false, QichiIcons.Repeat) },
    )
    if (parts.isEmpty()) return null
    return {
        val colors = QichiTheme.colors
        val type = QichiTheme.typography
        val small = type.caption.copy(fontSize = 12.tsp, color = colors.muted)
        val mono = type.numeral.copy(fontSize = 12.tsp, color = colors.muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { i, (text, digits, icon) ->
                if (i > 0) Text("·", style = small.copy(color = colors.faint))
                if (icon != null) Icon(icon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
                Text(text, style = if (digits) mono else small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}
