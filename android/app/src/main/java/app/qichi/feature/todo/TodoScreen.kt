package app.qichi.feature.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.relativeDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.page),
            ) {
                items(state.open, key = { it.todo.value.id }) { group ->
                    Column {
                        TodoRow(
                            item = group.todo, people = state.people, today = state.today, zone = state.zone,
                            onToggle = { viewModel.toggle(group.todo.value, it) },
                            onClick = { editing = group.todo.value.id.toString() },
                        )
                        group.children.forEach { child ->
                            TodoRow(
                                item = child, people = state.people, today = state.today, zone = state.zone, subtask = true,
                                onToggle = { viewModel.toggle(child.value, it) },
                                onClick = { editing = group.todo.value.id.toString() },
                            )
                        }
                    }
                }
                if (state.done.isNotEmpty()) {
                    item(key = "done-label") {
                        Spacer(Modifier.height(Spacing.detailSection))
                        SectionLabel("已完成")
                    }
                    items(state.done, key = { "done-" + it.todo.value.id }) { group ->
                        TodoRow(
                            item = group.todo, people = state.people, today = state.today, zone = state.zone,
                            onToggle = { viewModel.toggle(group.todo.value, it) },
                            onClick = { editing = group.todo.value.id.toString() },
                        )
                    }
                }
                item { Spacer(Modifier.height(Spacing.xxl)) }
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
