package app.qichi.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.shared.api.Plan
import app.qichi.shared.api.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.delay

// 待办的编辑面板：待办页和计划页（P14-03，计划里的待办点开就能改）共用。表单在 TodoForm.kt。

/**
 * 新建或编辑一条待办：标题、交给谁、截止、重复、属于哪个计划、备注、子任务；编辑时底部可以删除。
 * [group] 为空是新建。[plans] 是能挂的计划（子任务跟着父待办，不单独挂计划；没有计划时不显示这一栏）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditor(
    group: TodoGroup?,
    people: People,
    today: LocalDate,
    zone: ZoneId,
    plans: List<Plan>,
    initial: TodoForm,
    onDismiss: () -> Unit,
    onSave: (TodoForm) -> Unit,
    onDelete: () -> Unit,
    onAddSubtask: (String) -> Unit,
    onToggleChild: (Todo, Boolean) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var form by remember(group?.todo?.value?.id) { mutableStateOf(initial) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var subtask by remember { mutableStateOf("") }
    val titleFocus = remember { FocusRequester() }
    // 新建：一打开光标就在标题里，不用再点一下
    LaunchedEffect(Unit) {
        if (group == null) {
            delay(150)
            runCatching { titleFocus.requestFocus() }
        }
    }

    // 写了东西以后误滑、误点外面不直接丢掉
    DraftSheet(dirty = form != initial || subtask.isNotBlank(), onDismiss = onDismiss) {
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
                // 键盘上的「完成」直接保存（只写个标题就够的时候最快）
                keyboardActions = KeyboardActions(onDone = { if (form.canSave) onSave(form) }),
                focusRequester = titleFocus,
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
                    ChoicePill("不设", form.dueDate == null, { form = form.copy(dueDate = null, dueTime = null, repeat = Repeat.None) }, Modifier.weight(1f))
                    ChoicePill("今天", form.dueDate == today, { form = form.copy(dueDate = today) }, Modifier.weight(1f))
                    ChoicePill("明天", form.dueDate == today.plusDays(1), { form = form.copy(dueDate = today.plusDays(1)) }, Modifier.weight(1f))
                    val other = form.dueDate != null && form.dueDate != today && form.dueDate != today.plusDays(1)
                    ChoicePill("选日期", other, { pickingDate = true }, Modifier.weight(1f))
                }
                // 有日期时可以再加一个时刻：到点提醒（只有日期的不单独提醒）
                if (form.dueDate != null) {
                    Row(Modifier.padding(top = Spacing.xxs), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChoicePill("不定时刻", form.dueTime == null, { form = form.copy(dueTime = null) }, Modifier.weight(1f))
                        ChoicePill(form.dueTime?.let { "%02d:%02d 提醒".format(it.hour, it.minute) } ?: "加个时刻", form.dueTime != null, { pickingTime = true }, Modifier.weight(1f))
                    }
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
                            item = child, people = people, today = today, zone = zone, subtask = true,
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
                    TextAction("删除", onClick = { deleting = true })
                }
            }
            Spacer(Modifier.height(Spacing.s))
        }
    }

    if (deleting) {
        ConfirmDialog("删除这件待办？", "会进回收站，可以恢复" + if (group?.children?.isNotEmpty() == true) "；子任务一起删。" else "。", "删除", onConfirm = onDelete, onDismiss = { deleting = false })
    }

    if (pickingTime) {
        val start = form.dueTime ?: LocalTime.of(9, 0)
        val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = colors.paper,
            confirmButton = {
                TextAction("确定", onClick = {
                    form = form.copy(dueTime = LocalTime.of(timeState.hour, timeState.minute))
                    pickingTime = false
                })
            },
            dismissButton = { TextAction("取消", onClick = { pickingTime = false }, color = colors.muted) },
            text = { TimePicker(state = timeState) },
        )
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
