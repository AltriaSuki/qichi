package app.qichi.feature.plan

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BarAction
import app.qichi.core.designsystem.component.CheckCircle
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.FileUrls
import app.qichi.core.ui.PlanCover
import app.qichi.core.ui.StageTrack
import app.qichi.core.ui.TodoEditor
import app.qichi.core.ui.TodoForm
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.dotDate
import app.qichi.core.ui.relativeDay
import app.qichi.core.ui.shortDate
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.model.PlanStatus
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID


// ───────────────────────── 列表 ─────────────────────────

/**
 * 「一起 → 计划」：分进行中、放一放、已完成三段（P14-03）；进行中的按目标日排，过了目标日的标出来。
 * 设计稿没有这一页，按列表页的规则做。
 */
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
    var tab by rememberSaveable { mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Plan, onBack)
            Segmented(listOf("进行中", "放一放", "已完成"), tab, { tab = it })
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                val shown = when (tab) {
                    1 -> state.paused
                    2 -> state.done
                    else -> state.active
                }
                if (state.loaded && shown.isEmpty()) {
                    Text(
                        when (tab) {
                            1 -> "没有先放一放的计划。暂时不推进的，可以在计划右上角的菜单里「先放一放」。"
                            2 -> "还没有完成的计划。"
                            else -> "还没有计划。长一点的事，可以放在这里慢慢推进。"
                        },
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m),
                    )
                }
                shown.forEachIndexed { i, summary ->
                    PlanCard(summary, state.people, state.today, vm.urls, index = i, onClick = { onOpen(summary.plan.value.id) })
                }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("新计划", { creating = true })
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

/**
 * 列表里的一个计划：浮起的卡片、一条胶带、封面小图、标题、阶段线、下一步，下面一行小字是阶段、待办完成了几个、
 * 里程碑和目标日（过了目标日用暮玫瑰色写「过了 N 天」，P14-03）；卡片轻轻左右倾斜。
 */
@Composable
private fun PlanCard(summary: PlanSummary, people: People, today: LocalDate, urls: FileUrls, index: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val plan = summary.plan.value
    val done = plan.status == PlanStatus.Done
    val paused = plan.status == PlanStatus.Archived
    val rotation = listOf(-.6f, .5f, -.4f)[index % 3]
    val tape = listOf(colors.personB, colors.personA, colors.accent)[index % 3]
    Box(Modifier.rotate(rotation)) {
        Column(
            Modifier.fillMaxWidth().lift(colors).clip(QichiShapes.card).background(colors.card)
                .clickable(role = Role.Button, onClickLabel = "打开计划", onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PlanCover(plan, urls, Modifier.size(52.dp), RoundedCornerShape(10.dp), thumbWidth = 200)
                Text(plan.title, style = type.headline.copy(fontSize = 17.tsp, lineHeight = 25.tsp, color = if (done || paused) colors.muted else colors.ink),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PersonMark(people.markChar(plan.ownerId), people.person(plan.ownerId), size = 22.dp)
            }
            if (!done && summary.stages.isNotEmpty()) StageTrack(summary.stages, summary.currentStage, onToggle = null)
            if (!done && !paused) plan.nextStep?.let { step ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("下一步", style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = colors.accent), modifier = Modifier.padding(top = 3.dp))
                    Text(step, style = type.body.copy(color = colors.ink))
                }
            }
            val progress = summary.progress
            val overdueDays = plan.targetDate?.takeIf { isOverdue(plan, today) }?.let { ChronoUnit.DAYS.between(it, today) }
            val meta = buildList {
                when {
                    done -> plan.completedAt?.let { add("完成于 ${shortDate(it.atZone(ZoneId.systemDefault()).toLocalDate())}") }
                    else -> {
                        if (progress.stages > 0) add("阶段 ${progress.stagesDone}/${progress.stages}")
                        if (progress.todos > 0) add("待办 ${progress.todosDone}/${progress.todos}")
                        summary.nextMilestone?.let { add("里程碑 ${it.format(MD)}") }
                        if (overdueDays == null) plan.targetDate?.let { add("目标 ${relativeDay(it, today).first}") }
                    }
                }
            }
            if (meta.isNotEmpty() || overdueDays != null) {
                val text = buildAnnotatedString {
                    append(meta.joinToString(" · "))
                    if (overdueDays != null) {
                        if (meta.isNotEmpty()) append(" · ")
                        withStyle(SpanStyle(color = colors.accent)) { append("过了目标 $overdueDays 天") }
                    }
                }
                Text(text, style = type.caption.copy(fontSize = 12.tsp, color = colors.muted), modifier = Modifier.padding(top = 6.dp))
            }
        }
        Tape(Modifier.offset(x = 22.dp, y = (-8).dp), color = tape, width = 46.dp, rotation = rotation * 4)
    }
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")

// ───────────────────────── 详情 ─────────────────────────

/**
 * 计划详情（按 Plan.dc.html）：标题与负责人、阶段、下一步、里程碑、待办、记录。
 * P14-03：里面的东西都能点开改（待办用和待办页同一个编辑面板、里程碑长按、自己记的进展点一下）；右上角菜单里
 * 「先放一放」「接着做」「重新打开」；下一步可以用计划里的一件待办，做完一步后从没做完的待办里挑下一个。
 */
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
    var reopening by rememberSaveable { mutableStateOf(false) }
    var pickingNext by rememberSaveable { mutableStateOf(false) }
    // 正在改的待办（id）、里程碑、进展
    var editingTodo by rememberSaveable { mutableStateOf<String?>(null) }
    var editingMilestone by remember { mutableStateOf<Milestone?>(null) }
    var editingLog by remember { mutableStateOf<PlanLog?>(null) }
    val coverUpload by vm.coverUpload.collectAsStateWithLifecycle()
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setCover) }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.message.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    // 删除后（或别处删掉了）自动返回
    val plan = state.plan?.value
    LaunchedEffect(state.loaded, plan?.deletedAt) {
        if (state.loaded && (plan == null || plan.deletedAt != null)) onBack()
    }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        ItemTopBar(
            plan?.title.orEmpty(), onBack, feature = Feature.Plan,
            actions = if (plan != null) listOf(BarAction("编辑", QichiIcons.Pen, { editing = true })) else emptyList(),
            menu = if (plan != null) listOfNotNull(
                when (plan.status) {
                    PlanStatus.Active -> MenuAction("先放一放", { vm.setStatus(PlanStatus.Archived) })
                    PlanStatus.Archived -> MenuAction("接着做", { vm.setStatus(PlanStatus.Active) })
                    PlanStatus.Done -> MenuAction("重新打开", { reopening = true })
                },
                MenuAction("换封面照片", { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = coverUpload == null),
                if (plan.coverFileId != null) MenuAction("封面改回插画", vm::clearCover) else null,
            ) else emptyList(),
        )
        if (plan == null) return@Column
        val done = plan.status == PlanStatus.Done
        val paused = plan.status == PlanStatus.Archived
        val overdueDays = plan.targetDate?.takeIf { isOverdue(plan, state.today) }?.let { ChronoUnit.DAYS.between(it, state.today) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.page, end = Spacing.page, top = Spacing.xs, bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // 封面横幅（照片或插画）+ 胶带 + 还有几天
            Box(Modifier.fillMaxWidth()) {
                PlanCover(plan, vm.urls, Modifier.fillMaxWidth().height(116.dp), RoundedCornerShape(14.dp), thumbWidth = 800)
                Tape(Modifier.offset(x = 18.dp, y = (-8).dp), color = colors.accent, width = 60.dp, rotation = -6f, alpha = .35f)
                val daysLeft = plan.targetDate?.let { ChronoUnit.DAYS.between(state.today, it) }
                if (!done && !paused && daysLeft != null) {
                    // 过了目标日（P14-03）：同一张贴纸写「过了 N 天」
                    Sticker(
                        when {
                            daysLeft == 0L -> "就是今天"
                            daysLeft > 0 -> "还有 $daysLeft 天"
                            else -> "过了 ${-daysLeft} 天"
                        },
                        Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp).background(colors.card, RoundedCornerShape(14.dp)),
                        rotation = -4f,
                    )
                }
                coverUpload?.let { p ->
                    Text("正在上传 ${(p * 100).toInt()}%", style = type.caption.copy(color = colors.ink),
                        modifier = Modifier.align(Alignment.Center).background(colors.card.copy(alpha = .85f), QichiShapes.pill).padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PersonMark(state.people.markChar(plan.ownerId), state.people.person(plan.ownerId), size = 20.dp)
                    val progress = state.progress
                    Text(
                        buildAnnotatedString {
                            append("负责人 ${state.people.name(plan.ownerId)}")
                            plan.targetDate?.let {
                                append(" · ")
                                if (overdueDays != null) withStyle(SpanStyle(color = colors.accent)) { append("目标 ${relativeDay(it, state.today).first}，过了 $overdueDays 天") }
                                else append("目标 ${relativeDay(it, state.today).first}")
                            }
                            if (progress.stages > 0) append(" · 阶段 ${progress.stagesDone}/${progress.stages}")
                        },
                        style = type.caption.copy(color = colors.muted),
                    )
                }
                if (state.stages.isNotEmpty()) StageTrack(state.stages, state.currentStage, onToggle = vm::toggleStage)
            }

            when {
                done -> CompletionCard(plan)
                paused -> PausedCard(onResume = { vm.setStatus(PlanStatus.Active) })
                else -> NextStepCard(
                    plan, state.people, state.today, linked = state.linkedTodo != null,
                    onEdit = { editingStep = true },
                    onDone = {
                        // 做完一步：计划里还有没做完的待办时，接着挑下一个
                        val more = state.stepCandidates.isNotEmpty()
                        vm.completeNextStep()
                        if (more) pickingNext = true
                    },
                )
            }

            if (state.milestones.isNotEmpty()) {
                Column {
                    SectionLabel("里程碑", icon = QichiIcons.Flag, tint = colors.personB)
                    state.milestones.forEach { m ->
                        MilestoneRow(m, onToggle = { vm.toggleMilestone(m) }, onLongPress = { editingMilestone = m })
                    }
                }
            }

            Column {
                // 完成了几件 / 一共几件（P14-03）
                SectionLabel("待办", icon = QichiIcons.Todo, tint = colors.personB) {
                    if (state.progress.todos > 0) Text("${state.progress.todosDone}/${state.progress.todos}", style = type.numeral.copy(fontSize = 13.tsp, color = colors.muted))
                }
                state.openTodos.forEach { item ->
                    val open = { editingTodo = item.todo.value.id.toString() }
                    TodoRow(item.todo, state.people, state.today, state.zone, onToggle = { vm.toggleTodo(item.todo.value, it) }, onClick = open)
                    item.children.forEach { child ->
                        TodoRow(child, state.people, state.today, state.zone, subtask = true,
                            onToggle = { vm.toggleTodo(child.value, it) }, onClick = open, modifier = Modifier.padding(start = 36.dp))
                    }
                }
                if (!done) QuickAdd(label = "加一件待办", onAdd = vm::addTodo)
                state.doneTodos.forEach { item ->
                    TodoRow(item.todo, state.people, state.today, state.zone, onToggle = { vm.toggleTodo(item.todo.value, it) },
                        onClick = { editingTodo = item.todo.value.id.toString() })
                }
            }

            Column {
                SectionLabel("记录", icon = QichiIcons.Pen, tint = colors.accent)
                QuickAdd(label = "记一笔进展", onAdd = vm::addLog)
                // 每条下面写着谁记的；自己记的点一下可以改、可以删（P14-03）
                state.logs.forEach { log ->
                    val mine = log.authorId == state.people.myUserId
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (mine) Modifier.clickable(role = Role.Button, onClickLabel = "修改这条进展") { editingLog = log } else Modifier)
                            .padding(vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.ml),
                    ) {
                        Text(shortDate(log.createdAt.atZone(state.zone).toLocalDate()),
                            style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted), modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(log.body, style = type.body.copy(fontSize = 14.tsp, color = colors.ink))
                            Text(state.people.name(log.authorId), style = type.caption.copy(fontSize = 12.tsp, color = colors.faint))
                        }
                    }
                }
                // 最早的一条：计划从哪天开始
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(shortDate(plan.createdAt.atZone(state.zone).toLocalDate()),
                        style = type.numeral.copy(fontSize = 17.tsp, color = colors.muted), modifier = Modifier.width(56.dp))
                    Text("开始", style = type.body.copy(fontSize = 14.tsp, color = colors.ink))
                }
            }

            if (!done && !paused) {
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
            onMoveStage = vm::moveStage,
            onAddMilestone = vm::addMilestone, onEditMilestone = { editingMilestone = it }, onDeleteMilestone = vm::deleteMilestone,
            onDeletePlan = { editing = false; deleting = true },
        )
    }
    if (editingStep) {
        NextStepEditor(
            plan, state.people, state.today, state.zone,
            candidates = state.stepCandidates,
            linked = state.linkedTodo,
            onDismiss = { editingStep = false },
            onLink = { todo -> editingStep = false; vm.linkNextStep(todo) },
            onSave = { text, owner, due, asTodo ->
                editingStep = false
                vm.setNextStep(text, owner, due, asTodo)
            },
        )
    }
    if (pickingNext) {
        PickNextStepSheet(
            candidates = state.stepCandidates, people = state.people, today = state.today, zone = state.zone,
            onPick = { todo -> pickingNext = false; vm.linkNextStep(todo) },
            onWrite = { pickingNext = false; editingStep = true },
            onDismiss = { pickingNext = false },
        )
    }
    if (completing) {
        // 重新打开过的计划：上次的完成记录先填上，可以接着改（P14-03）
        CompletePlanDialog(initial = plan.completionNote.orEmpty(), onDismiss = { completing = false }, onConfirm = { note -> completing = false; vm.complete(note) })
    }
    if (reopening) {
        ConfirmDialog(
            title = "重新打开这个计划？",
            text = "它回到「进行中」，完成日期清掉；完成记录还留着，再完成时可以接着改。",
            confirmLabel = "重新打开",
            onConfirm = { reopening = false; vm.setStatus(PlanStatus.Active) },
            onDismiss = { reopening = false },
        )
    }
    editingTodo?.let { key ->
        val group = state.findTodo(UUID.fromString(key))
        if (group == null) {
            editingTodo = null
            return@let
        }
        TodoEditor(
            group = group, people = state.people, today = state.today, zone = state.zone, plans = state.plans,
            initial = TodoForm.of(group.todo.value, state.people, state.zone),
            onDismiss = { editingTodo = null },
            onSave = { form -> vm.editTodo(group.todo.value, form); editingTodo = null },
            onDelete = { vm.deleteTodo(group); editingTodo = null },
            onAddSubtask = { title -> vm.addSubtask(group.todo.value, title) },
            onToggleChild = { child, isDone -> vm.toggleTodo(child, isDone) },
        )
    }
    editingMilestone?.let { m ->
        MilestoneEditor(
            m, state.today,
            onDismiss = { editingMilestone = null },
            onSave = { title, date -> editingMilestone = null; vm.editMilestone(m, title, date) },
            onDelete = { editingMilestone = null; vm.deleteMilestone(m) },
        )
    }
    editingLog?.let { log ->
        LogEditor(
            log,
            onDismiss = { editingLog = null },
            onSave = { body -> editingLog = null; vm.editLog(log, body) },
            onDelete = { editingLog = null; vm.deleteLog(log) },
        )
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
}

/**
 * 下一步：暮玫瑰淡底的浮起卡片，小字「下一步」、这件事、谁 / 什么时候、「完成」。
 * [linked]：用的是计划里的一件待办（P14-03），标一行小字；「完成」就是勾掉那件待办。
 */
@Composable
private fun NextStepCard(plan: Plan, people: People, today: LocalDate, linked: Boolean, onEdit: () -> Unit, onDone: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(
        Modifier.fillMaxWidth().lift(colors).clip(QichiShapes.card).background(colors.card).background(colors.accent.copy(alpha = .1f))
            .clickable(role = Role.Button, onClickLabel = "修改下一步", onClick = onEdit)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Text("下一步", style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = colors.accent))
        val step = plan.nextStep
        if (step == null) {
            Text("写下下一步要做的一件小事", style = type.body.copy(fontSize = 17.tsp, color = colors.faint),
                modifier = Modifier.padding(top = 2.dp, bottom = Spacing.s))
        } else {
            Text(step, style = type.headline.copy(fontSize = 17.tsp, fontWeight = FontWeight.W600, lineHeight = 25.5.tsp, color = colors.ink),
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
            if (linked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Icon(QichiIcons.Todo, contentDescription = null, tint = colors.muted, modifier = Modifier.size(12.dp))
                    Text("计划里的待办，做完它这一步就完成了", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.nextStepOwnerId?.let { PersonMark(people.markChar(it), people.person(it), size = 18.dp) }
                    plan.nextStepDue?.let {
                        Text("${relativeDay(it, today).first}截止", style = type.caption.copy(color = if (it.isBefore(today)) colors.accent else colors.muted))
                    }
                }
                TextAction("完成", onDone)
            }
        }
    }
}

/** 先放一放的计划（P14-03）：一张雾面卡片说明一下，「接着做」回到进行中。 */
@Composable
private fun PausedCard(onResume: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    MistCard {
        Text("先放一放了", style = type.caption.copy(fontSize = 12.tsp, letterSpacing = 0.3.em, color = colors.accent))
        Text("想接着推进时点「接着做」，下一步、待办和记录都还在。", style = type.body.copy(color = colors.muted),
            modifier = Modifier.padding(top = Spacing.xxs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextAction("接着做", onResume)
        }
    }
}

@Composable
private fun CompletionCard(plan: Plan) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    MistCard {
        val date = plan.completedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
        Text(if (date != null) "完成于 ${dotDate(date, withYear = true)}" else "已完成",
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
                onLongClickLabel = "修改", onLongClick = onLongPress),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) CheckCircle(checked = true, onCheckedChange = null, size = 20.dp, modifier = Modifier.size(20.dp))
            else Box(Modifier.size(9.dp).rotate(45f).background(colors.personB))
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
