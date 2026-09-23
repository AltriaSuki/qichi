package app.qichi.feature.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.monthRoman
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanStage
import app.qichi.shared.model.PlanStatus
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 「3 · ix」这样的日期写法（设计稿里的里程碑、记录）。 */
internal fun shortDate(date: LocalDate): String = "${date.dayOfMonth} · ${monthRoman(date.monthValue)}"

// ───────────────────────── 列表 ─────────────────────────

/** 「一起 → 计划」：进行中的计划在前，完成的收在下面。设计稿没有这一页，按列表页的规则做。 */
@Composable
fun PlanListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    vm: PlanListViewModel = hiltViewModel<PlanListViewModel, PlanListViewModel.Factory>(key = "plans-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var creating by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("计划", onBack) {
            IconAction(QichiIcons.Plus, "新计划", { creating = true })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (state.loaded && state.active.isEmpty() && state.done.isEmpty()) {
                Text("还没有计划。长一点的事，可以放在这里慢慢推进。", style = type.caption.copy(color = colors.muted),
                    modifier = Modifier.padding(top = Spacing.m))
                TextAction("新建一个计划", { creating = true })
            }
            state.active.forEach { PlanRow(it, state.people, state.today, onClick = { onOpen(it.plan.value.id) }) }
            if (state.done.isNotEmpty()) {
                SectionLabel("已完成 ${state.done.size}", modifier = Modifier.padding(top = Spacing.l)) {
                    TextAction(if (showDone) "收起" else "展开", { showDone = !showDone }, color = colors.muted)
                }
                if (showDone) state.done.forEach { PlanRow(it, state.people, state.today, onClick = { onOpen(it.plan.value.id) }) }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (creating) {
        PlanEditor(
            title = "新计划",
            initialTitle = "",
            initialOwner = state.people.myUserId,
            initialTarget = null,
            people = state.people,
            today = state.today,
            onDismiss = { creating = false },
            onSave = { t, owner, target ->
                creating = false
                vm.create(t, owner, target, onCreated = onOpen)
            },
        )
    }
}

@Composable
private fun PlanRow(summary: PlanSummary, people: People, today: LocalDate, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val plan = summary.plan.value
    val done = plan.status == PlanStatus.Done
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开计划", onClick = onClick).padding(vertical = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(plan.title, style = type.feeling.copy(fontSize = 22.tsp, lineHeight = 30.tsp, color = if (done) colors.muted else colors.ink),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PersonMark(people.markChar(plan.ownerId), people.person(plan.ownerId), size = 22.dp)
        }
        val meta = buildList {
            when {
                done -> plan.completedAt?.let { add("完成于 ${shortDate(it.atZone(ZoneId.systemDefault()).toLocalDate())}") }
                summary.currentStage != null -> add(summary.currentStage.title)
            }
            if (!done) plan.nextStep?.let { add("下一步：$it") }
            if (!done && summary.openTodos > 0) add("${summary.openTodos} 件待办")
        }
        if (meta.isNotEmpty()) {
            Text(meta.joinToString("  ·  "), style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs))
        }
        if (!done) plan.targetDate?.let {
            Text("目标 ${relativeDay(it, today).first}", style = type.caption.copy(color = if (it.isBefore(today)) colors.accent else colors.muted))
        }
    }
}

// ───────────────────────── 详情 ─────────────────────────

/** 计划详情（按 Plan.dc.html）：标题与负责人、阶段、下一步、里程碑、待办、记录。 */
@Composable
fun PlanDetailScreen(
    roomId: UUID,
    planId: UUID,
    onBack: () -> Unit,
    vm: PlanDetailViewModel = hiltViewModel<PlanDetailViewModel, PlanDetailViewModel.Factory>(key = "plan-$planId") { it.create(roomId, planId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingStep by rememberSaveable { mutableStateOf(false) }
    var completing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var removingMilestone by remember { mutableStateOf<Milestone?>(null) }

    // 删除后（或别处删掉了）自动返回
    val plan = state.plan?.value
    LaunchedEffect(state.loaded, plan?.deletedAt) {
        if (state.loaded && (plan == null || plan.deletedAt != null)) onBack()
    }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        BackBar("计划", onBack) {
            if (plan != null) TextAction("编辑", { editing = true }, color = colors.muted)
        }
        if (plan == null) return@Column
        val done = plan.status == PlanStatus.Done
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // 标题与负责人
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(plan.title, style = type.hubTitle.copy(fontSize = 30.tsp, lineHeight = 40.tsp, letterSpacing = 0.14.em, color = colors.ink),
                        modifier = Modifier.weight(1f).semantics { heading() })
                    PersonMark(state.people.markChar(plan.ownerId), state.people.person(plan.ownerId), size = 26.dp,
                        modifier = Modifier.semantics { contentDescription = "负责人 ${state.people.name(plan.ownerId)}" })
                }
                plan.targetDate?.let {
                    Text("目标 ${relativeDay(it, state.today).first}", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.xxs))
                }
                if (state.stages.isNotEmpty()) StageTrack(state.stages, state.currentStage, onToggle = vm::toggleStage)
            }

            if (done) {
                CompletionCard(plan)
            } else {
                NextStepCard(plan, state.people, state.today, onEdit = { editingStep = true }, onDone = vm::completeNextStep)
            }

            if (state.milestones.isNotEmpty()) {
                Column {
                    SectionLabel("里程碑")
                    state.milestones.forEach { m ->
                        MilestoneRow(m, onToggle = { vm.toggleMilestone(m) }, onLongPress = { removingMilestone = m })
                    }
                }
            }

            Column {
                SectionLabel("待办")
                state.openTodos.forEach { item ->
                    TodoRow(item.todo, state.people, state.today, state.zone, onToggle = { vm.toggleTodo(item.todo.value, it) }, onClick = {})
                    item.children.forEach { child ->
                        TodoRow(child, state.people, state.today, state.zone, subtask = true,
                            onToggle = { vm.toggleTodo(child.value, it) }, onClick = {}, modifier = Modifier.padding(start = 36.dp))
                    }
                }
                if (!done) QuickAdd(label = "加一件待办", onAdd = vm::addTodo)
                state.doneTodos.forEach { item ->
                    TodoRow(item.todo, state.people, state.today, state.zone, onToggle = { vm.toggleTodo(item.todo.value, it) }, onClick = {})
                }
            }

            Column {
                SectionLabel("记录")
                QuickAdd(label = "记一笔进展", onAdd = vm::addLog)
                state.logs.forEach { log ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(shortDate(log.createdAt.atZone(state.zone).toLocalDate()),
                            style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted), modifier = Modifier.width(56.dp))
                        Text(log.body, style = type.body.copy(fontSize = 14.tsp, color = colors.ink), modifier = Modifier.weight(1f))
                    }
                }
                // 最早的一条：计划从哪天开始
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(shortDate(plan.createdAt.atZone(state.zone).toLocalDate()),
                        style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted), modifier = Modifier.width(56.dp))
                    Text("开始", style = type.body.copy(fontSize = 14.tsp, color = colors.ink))
                }
            }

            if (!done) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextAction("完成这个计划", { completing = true })
                }
            }
        }
    }

    if (plan == null) return
    if (editing) {
        PlanFullEditor(
            plan = plan, stages = state.stages, milestones = state.milestones, people = state.people, today = state.today,
            onDismiss = { editing = false },
            onSave = { t, owner, target -> vm.edit(t, owner, target) },
            onAddStage = vm::addStage, onRenameStage = vm::renameStage, onDeleteStage = vm::deleteStage,
            onAddMilestone = vm::addMilestone, onDeleteMilestone = vm::deleteMilestone,
            onDeletePlan = { editing = false; deleting = true },
        )
    }
    if (editingStep) {
        NextStepEditor(plan, state.people, state.today, onDismiss = { editingStep = false }, onSave = { text, owner, due ->
            editingStep = false
            vm.setNextStep(text, owner, due)
        })
    }
    if (completing) {
        CompletePlanDialog(onDismiss = { completing = false }, onConfirm = { note -> completing = false; vm.complete(note) })
    }
    if (deleting) {
        ConfirmDialog(
            title = "删除这个计划？",
            text = "计划会进回收站，可以恢复。里面的待办不会被删除。",
            confirmLabel = "删除",
            onConfirm = vm::delete,
            onDismiss = { deleting = false },
        )
    }
    removingMilestone?.let { m ->
        ConfirmDialog(
            title = "删除这个里程碑？",
            text = m.title,
            confirmLabel = "删除",
            onConfirm = { vm.deleteMilestone(m) },
            onDismiss = { removingMilestone = null },
        )
    }
}

/**
 * 阶段进度线（设计稿）：完成的阶段是实心小点，当前阶段是 accent 大点，后面的阶段是空心点；
 * 当前阶段之前的连线用 ink，之后的用 line2。点阶段名切换完成与否。
 */
@Composable
private fun StageTrack(stages: List<PlanStage>, current: PlanStage?, onToggle: (PlanStage) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val currentIndex = current?.let { stages.indexOf(it) } ?: stages.size
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { i, _ ->
                if (i > 0) Box(Modifier.weight(1f).height(1.dp).background(if (i <= currentIndex) colors.ink else colors.line2))
                when {
                    i < currentIndex -> Box(Modifier.size(7.dp).background(colors.ink, CircleShape))
                    i == currentIndex -> Box(Modifier.size(11.dp).background(colors.personA, CircleShape))
                    else -> Box(Modifier.size(7.dp).border(1.dp, colors.faint, CircleShape))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            stages.forEachIndexed { i, stage ->
                val isCurrent = i == currentIndex
                Text(
                    stage.title,
                    style = type.caption.copy(
                        letterSpacing = 0.14.em,
                        fontWeight = if (isCurrent) FontWeight.W400 else FontWeight.W300,
                        color = if (isCurrent) colors.ink else colors.muted,
                    ),
                    textAlign = when {
                        stages.size == 1 -> TextAlign.Start
                        i == 0 -> TextAlign.Start
                        i == stages.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Sizes.touchTarget)
                        .clickable(role = Role.Checkbox, onClickLabel = if (stage.doneAt == null) "标为完成" else "标为未完成") { onToggle(stage) }
                        .semantics { contentDescription = "${stage.title}，${if (stage.doneAt != null) "已完成" else if (isCurrent) "当前阶段" else "未开始"}" }
                        .padding(top = Spacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun NextStepCard(plan: Plan, people: People, today: LocalDate, onEdit: () -> Unit, onDone: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    MistCard(Modifier.clickable(role = Role.Button, onClickLabel = "修改下一步", onClick = onEdit)) {
        Text("下一步", style = type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.3.em, color = colors.accent))
        val step = plan.nextStep
        if (step == null) {
            Text("写下下一步要做的一件小事", style = type.body.copy(fontSize = 17.tsp, color = colors.faint),
                modifier = Modifier.padding(top = 6.dp, bottom = Spacing.s))
        } else {
            Text(step, style = type.feeling.copy(fontSize = 19.tsp, lineHeight = 28.tsp, letterSpacing = 0.06.em, color = colors.ink),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    plan.nextStepOwnerId?.let { PersonMark(people.markChar(it), people.person(it), size = 18.dp) }
                    plan.nextStepDue?.let {
                        Text(relativeDay(it, today).first, style = type.caption.copy(color = if (it.isBefore(today)) colors.accent else colors.muted))
                    }
                }
                TextAction("完成", onDone)
            }
        }
    }
}

@Composable
private fun CompletionCard(plan: Plan) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    MistCard {
        val date = plan.completedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
        Text(if (date != null) "完成于 ${date.year} · ${date.monthValue} · ${date.dayOfMonth}" else "已完成",
            style = type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.3.em, color = colors.accent))
        plan.completionNote?.let {
            Text(it, style = type.reading.copy(color = colors.ink), modifier = Modifier.padding(top = 6.dp, bottom = Spacing.s))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MilestoneRow(m: Milestone, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val done = m.doneAt != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRow)
            .combinedClickable(role = Role.Checkbox, onClickLabel = if (done) "标为未完成" else "标为完成", onClick = onToggle,
                onLongClickLabel = "删除", onLongClick = onLongPress),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) CheckCircle(checked = true, onCheckedChange = null, size = 20.dp, modifier = Modifier.size(20.dp))
            else Box(Modifier.size(8.dp).background(colors.personB))
        }
        Text(m.title, style = type.bodyLarge.copy(color = if (done) colors.faint else colors.ink), modifier = Modifier.weight(1f))
        m.targetDate?.let { Text(shortDate(it), style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted)) }
    }
}

/** 列表末尾的一行输入：回车即添加。 */
@Composable
private fun QuickAdd(label: String, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    QichiTextField(
        value = text, onValueChange = { text = it }, label = label,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (text.isNotBlank()) {
                onAdd(text)
                text = ""
            }
        }),
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}
