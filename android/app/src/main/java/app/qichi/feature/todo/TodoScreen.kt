package app.qichi.feature.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.TodoEditor
import app.qichi.core.ui.TodoForm
import app.qichi.core.ui.TodoGroup
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.isLate
import app.qichi.core.ui.rememberMinuteNow
import app.qichi.shared.rules.Recurrence
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 待办页：进行中（按截止日）与已完成；点一条打开编辑，右下角新建。
 * [open]：一进来就打开的编辑面板——一条待办的 id，或 [TodoEditTarget.NEW] 新建（桌面组件点进来的深链 `todo/{id}`、`todo/new`，P15-02）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    roomId: UUID,
    onBack: () -> Unit,
    open: String? = null,
    viewModel: TodoViewModel = hiltViewModel<TodoViewModel, TodoViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    // null = 不显示；"new" = 新建；其它 = 编辑这条
    var editing by rememberSaveable { mutableStateOf(open) }

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
                // 一件都没有时说一句，不留一整页空白
                if (state.loaded && state.open.isEmpty() && state.done.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "还没有待办。要做的事记在这里，今天到期的会出现在今天页和桌面组件上。",
                            style = QichiTheme.typography.caption.copy(color = colors.muted),
                            modifier = Modifier.padding(top = Spacing.m),
                        )
                    }
                } else if (state.loaded && state.open.isEmpty()) {
                    item(key = "all-done") {
                        Text("都做完了。", style = QichiTheme.typography.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                    }
                }
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
        Fab("新待办", { editing = TodoEditTarget.NEW })
    }

    editing?.let { key ->
        val group = when (val target = TodoEditTarget.of(key, state.loaded, state::find)) {
            TodoEditTarget.New -> null
            is TodoEditTarget.Existing -> target.group
            TodoEditTarget.Waiting -> return@let
            TodoEditTarget.Gone -> {
                editing = null
                return@let
            }
        }
        TodoEditor(
            group = group,
            people = state.people,
            today = state.today,
            zone = state.zone,
            plans = state.plansFor(group?.todo?.value),
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

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val MD = DateTimeFormatter.ofPattern("MM.dd")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

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
    // 有时刻的（到点提醒）在每一段都写出时刻
    val time = todo.dueAt?.atZone(state.zone)?.format(HM)
    val plan = todo.planId?.let { state.planTitles[it] }
    val progress = group.children.takeIf { it.isNotEmpty() }?.let { c -> "${c.count { it.value.doneAt != null }}/${c.size}" }
    val repeat = todo.recurrence?.let(Recurrence::parse)?.let { r ->
        when (r.freq) {
            Recurrence.Freq.DAILY -> "每天"
            Recurrence.Freq.WEEKLY -> "每周"
            Recurrence.Freq.MONTHLY -> "每月"
        }
    }
    // 「今天」那一段不写日期，过期的写在右边；有小字的行右边不写（见 TodoRow），过期的日期就放进小字，不然看不出过期了
    val overdueDigits = if (!showDue && due != null && due.isBefore(state.today)) due.format(MD) else null
    // 每一段：文字、是否等宽（数字）、前面的小图标、是不是截止（过了截止时标醒目的颜色）
    val parts = listOfNotNull(
        dueText?.let { MetaPart(it, false, null, due = true) },
        dueDigits?.let { MetaPart(it, true, null, due = true) },
        overdueDigits?.let { MetaPart(it, true, null, due = true) },
        time?.let { MetaPart(it, true, QichiIcons.Clock, due = true) },
        plan?.let { MetaPart(it, false, QichiIcons.Flag) },
        progress?.let { MetaPart(it, true, null) },
        repeat?.let { MetaPart(it, false, QichiIcons.Repeat) },
    )
    // 只有过期日期、别的都没有：不要小字，照旧写在右边
    if (parts.all { it.due && it.text == overdueDigits }) return null
    return {
        val colors = QichiTheme.colors
        val type = QichiTheme.typography
        // 过了截止（日期过了，或今天的时刻已经过了）：截止那几段用醒目的颜色（P21-13）
        val now = rememberMinuteNow(active = todo.doneAt == null && todo.dueAt != null)
        val late = todo.isLate(state.today, state.zone, now)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { i, part ->
                val color = if (late && part.due) colors.accent else colors.muted
                if (i > 0) Text("·", style = type.caption.copy(fontSize = 12.tsp, color = colors.faint))
                if (part.icon != null) Icon(part.icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                Text(
                    part.text, style = (if (part.digits) type.numeral else type.caption).copy(fontSize = 12.tsp, color = color),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

private class MetaPart(val text: String, val digits: Boolean, val icon: ImageVector?, val due: Boolean = false)
