package app.qichi.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.ui.DateChoice
import app.qichi.core.ui.shortDate
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanStage
import app.qichi.shared.rules.Limits
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 新建计划：标题、负责人、目标日（可以不设）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanEditor(
    title: String,
    initialTitle: String,
    initialOwner: UUID?,
    initialTarget: LocalDate?,
    people: People,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, UUID, LocalDate?) -> Unit,
) {
    val colors = QichiTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var name by rememberSaveable { mutableStateOf(initialTitle) }
        var owner by remember { mutableStateOf(initialOwner ?: people.myUserId) }
        var target by remember { mutableStateOf(initialTarget) }
        SheetColumn {
            QichiTextField(name, { name = it.take(Limits.PLAN_TITLE_LENGTH.last) }, label = title, placeholder = "比如：秋天去一次海边",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            OwnerChoice("负责人", owner, people, allowBoth = false) { owner = it }
            DateChoice("目标日", target, today) { target = it }
            PrimaryButton("保存", onClick = { owner?.let { onSave(name, it, target) } }, enabled = name.isNotBlank() && owner != null,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 编辑计划：基本信息，以及阶段、里程碑的增删改；底部可以删除计划。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanFullEditor(
    plan: Plan,
    stages: List<PlanStage>,
    milestones: List<Milestone>,
    people: People,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, UUID, LocalDate?) -> Unit,
    onAddStage: (String) -> Unit,
    onRenameStage: (PlanStage, String) -> Unit,
    onDeleteStage: (PlanStage) -> Unit,
    onAddMilestone: (String, LocalDate?) -> Unit,
    onDeleteMilestone: (Milestone) -> Unit,
    onDeletePlan: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var name by rememberSaveable { mutableStateOf(plan.title) }
    var owner by remember { mutableStateOf(plan.ownerId) }
    var target by remember { mutableStateOf(plan.targetDate) }
    // 改过的阶段名：按键盘「完成」或关掉面板时保存
    val stageTexts = remember { mutableStateMapOf<UUID, String>() }
    // 关掉面板时保存基本信息与改过的阶段名（增删阶段、里程碑是每一步单独保存的）
    val close = {
        if (name.isNotBlank()) onSave(name, owner, target)
        stages.forEach { stage -> stageTexts[stage.id]?.let { if (it.isNotBlank() && it.trim() != stage.title) onRenameStage(stage, it) } }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        SheetColumn {
            QichiTextField(name, { name = it.take(Limits.PLAN_TITLE_LENGTH.last) }, label = "计划")
            OwnerChoice("负责人", owner, people, allowBoth = false) { it?.let { id -> owner = id } }
            DateChoice("目标日", target, today) { target = it }

            Column {
                SectionLabel("阶段")
                stages.forEach { stage ->
                    val text = stageTexts[stage.id] ?: stage.title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QichiTextField(text, { stageTexts[stage.id] = it.take(Limits.PLAN_TITLE_LENGTH.last) }, label = "阶段 ${stages.indexOf(stage) + 1}",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onRenameStage(stage, text) }))
                        IconAction(QichiIcons.Close, "删除阶段", { onDeleteStage(stage) }, tint = colors.muted, iconSize = 18)
                    }
                }
                var newStage by rememberSaveable { mutableStateOf("") }
                QichiTextField(newStage, { newStage = it }, label = "加一个阶段", placeholder = "比如：选地方",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newStage.isNotBlank()) { onAddStage(newStage); newStage = "" } }))
            }

            Column {
                SectionLabel("里程碑")
                milestones.forEach { m ->
                    Row(Modifier.heightIn(min = Sizes.listRow), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, style = type.bodyLarge.copy(color = colors.ink), modifier = Modifier.weight(1f))
                        m.targetDate?.let { Text(shortDate(it), style = type.numeral.copy(color = colors.muted)) }
                        IconAction(QichiIcons.Close, "删除里程碑", { onDeleteMilestone(m) }, tint = colors.muted, iconSize = 18)
                    }
                }
                var newMilestone by rememberSaveable { mutableStateOf("") }
                var milestoneDate by remember { mutableStateOf<LocalDate?>(null) }
                QichiTextField(newMilestone, { newMilestone = it }, label = "加一个里程碑", placeholder = "比如：订好住处")
                DateChoice(null, milestoneDate, today) { milestoneDate = it }
                TextAction("添加里程碑", {
                    onAddMilestone(newMilestone, milestoneDate)
                    newMilestone = ""
                    milestoneDate = null
                }, enabled = newMilestone.isNotBlank())
            }

            PrimaryButton("完成编辑", onClick = close, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextAction("删除计划", onDeletePlan, color = colors.muted)
            }
        }
    }
}

/** 下一步：一件具体的小事，谁来做、哪天之前。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NextStepEditor(
    plan: Plan,
    people: People,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, UUID?, LocalDate?) -> Unit,
) {
    val colors = QichiTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var text by rememberSaveable { mutableStateOf(plan.nextStep.orEmpty()) }
        var owner by remember { mutableStateOf(plan.nextStepOwnerId) }
        var due by remember { mutableStateOf(plan.nextStepDue) }
        SheetColumn {
            QichiTextField(text, { text = it.take(Limits.PLAN_STEP_LENGTH.last) }, label = "下一步", placeholder = "比如：对比三家民宿的价格和交通",
                singleLine = false)
            OwnerChoice("谁来做", owner, people, allowBoth = true) { owner = it }
            DateChoice("哪天之前", due, today) { due = it }
            PrimaryButton("保存", onClick = { onSave(text, owner, due) }, modifier = Modifier.fillMaxWidth())
            if (plan.nextStep != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextAction("清空下一步", { onSave("", null, null) }, color = colors.muted)
                }
            }
        }
    }
}

/** 完成计划时写一段完成记录（必填：回看时能知道结果怎样）。 */
@Composable
internal fun CompletePlanDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
        title = { Text("完成这个计划", style = type.pageTitle.copy(color = colors.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("写几句结果：做成了什么、有什么想记住的。", style = type.body.copy(color = colors.muted))
                QichiTextField(note, { note = it.take(Limits.PLAN_LOG_LENGTH.last) }, label = "完成记录", singleLine = false)
            }
        },
        confirmButton = { TextAction("完成", { onConfirm(note) }, enabled = note.isNotBlank()) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(horizontal = Spacing.page, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        content()
        Spacer(Modifier.height(Spacing.s))
    }
}

/** 选人：我 / 对方（[allowBoth] 时还有「两个人」= 空）。 */
@Composable
private fun OwnerChoice(label: String, selected: UUID?, people: People, allowBoth: Boolean, onSelect: (UUID?) -> Unit) {
    Column {
        SectionLabel(label)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowBoth) ChoicePill("两个人", selected == null, { onSelect(null) }, Modifier.weight(1f))
            people.me?.let { me -> ChoicePill(me.displayName, selected == me.userId, { onSelect(me.userId) }, Modifier.weight(1f)) }
            people.partner?.let { p -> ChoicePill(p.displayName, selected == p.userId, { onSelect(p.userId) }, Modifier.weight(1f)) }
        }
    }
}

