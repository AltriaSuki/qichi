package app.qichi.feature.decisions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.ChoicePill
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.DateChoice
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Decision
import app.qichi.shared.rules.Limits
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private fun reviewText(d: Decision, today: LocalDate): String? = d.reviewDate?.let {
    if (!it.isAfter(today)) "该复查了" else "复查 ${relativeDay(it, today).first}"
}

// ───────────────────────── 列表 ─────────────────────────

/** 决定记录：还没定的在前；定了的里面，复查日期到了的排前面并标出来。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionListScreen(
    roomId: UUID,
    onBack: () -> Unit,
    onOpen: (UUID) -> Unit,
    vm: DecisionListViewModel = hiltViewModel<DecisionListViewModel, DecisionListViewModel.Factory>(key = "decisions-$roomId") { it.create(roomId) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var creating by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackBar("决定", onBack) { IconAction(QichiIcons.Plus, "新的决定", { creating = true }) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            if (state.loaded && state.open.isEmpty() && state.decided.isEmpty()) {
                Text("还没有决定记录。要一起拿主意的事，可以把备选和各自在意的地方写下来，慢慢商量。",
                    style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                TextAction("记一个", { creating = true })
            }
            if (state.open.isNotEmpty()) {
                SectionLabel("还没定  ${state.open.size}", modifier = Modifier.padding(top = Spacing.s))
                state.open.forEach { DecisionRow(it, state.today) { onOpen(it.value.id) } }
            }
            if (state.decided.isNotEmpty()) {
                SectionLabel("定下了  ${state.decided.size}", modifier = Modifier.padding(top = Spacing.l))
                state.decided.forEach { DecisionRow(it, state.today) { onOpen(it.value.id) } }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (creating) {
        ModalBottomSheet(onDismissRequest = { creating = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.background) {
            var question by rememberSaveable { mutableStateOf("") }
            var options by rememberSaveable { mutableStateOf("") }
            var review by remember { mutableStateOf<LocalDate?>(null) }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(horizontal = Spacing.page, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                QichiTextField(question, { question = it.take(Limits.DECISION_QUESTION_LENGTH.last) }, label = "要决定什么", placeholder = "比如：搬到哪里？",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                QichiTextField(options, { options = it }, label = "备选（一行一个，可以之后再加）", singleLine = false)
                DateChoice("复查日期", review, state.today) { review = it }
                PrimaryButton("记下", {
                    creating = false
                    vm.create(question, options.lines(), review, onCreated = onOpen)
                }, enabled = question.isNotBlank(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DecisionRow(local: Local<Decision>, today: LocalDate, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val d = local.value
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick).padding(vertical = Spacing.s)) {
        Text(d.question, style = type.feeling.copy(fontSize = 20.tsp, lineHeight = 28.tsp, color = colors.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = Spacing.xxs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val meta = buildList {
                if (d.finalChoice != null) add("定了：${d.finalChoice}") else if (d.options.isNotEmpty()) add("${d.options.size} 个备选")
                if (d.concerns.isNotEmpty()) add("${d.concerns.size} 人写了关注点")
            }
            Text(meta.joinToString(" · "), style = type.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            reviewText(d, today)?.let { Text(it, style = type.caption.copy(color = if (d.reviewDate!!.isAfter(today)) colors.faint else colors.accent)) }
        }
    }
}

// ───────────────────────── 详情 ─────────────────────────

/** 一个决定：问题、备选（可增删）、两个人各自的关注点、最终决定、复查日期。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DecisionDetailScreen(
    roomId: UUID,
    decisionId: UUID,
    onBack: () -> Unit,
    vm: DecisionDetailViewModel = hiltViewModel<DecisionDetailViewModel, DecisionDetailViewModel.Factory>(key = "decision-$decisionId") {
        it.create(roomId, decisionId)
    },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var menu by remember { mutableStateOf(false) }
    var editingQuestion by remember { mutableStateOf(false) }
    var editingConcern by remember { mutableStateOf(false) }
    var deciding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var newOption by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.loaded, state.decision) { if (state.loaded && state.decision == null) onBack() }
    val d = state.decision?.value
    val people = state.people

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        BackBar("决定", onBack) {
            Box {
                IconAction(QichiIcons.More, "更多", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.paper) {
                    DropdownMenuItem(text = { Text("改问题", style = type.body) }, onClick = { menu = false; editingQuestion = true })
                    DropdownMenuItem(text = { Text("删除", style = type.body.copy(color = colors.accent)) }, onClick = { menu = false; deleting = true })
                }
            }
        }
        if (d == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Text(d.question, style = type.pageTitle.copy(fontSize = 24.tsp, lineHeight = 34.tsp, color = colors.ink),
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "改问题") { editingQuestion = true }.semantics { heading() })

            // ── 最终决定 ──
            if (d.finalChoice != null) {
                MistCard(Modifier.padding(top = Spacing.l)) {
                    Text("定了", style = type.caption.copy(color = colors.accent))
                    Text(d.finalChoice!!, style = type.feeling.copy(fontSize = 22.tsp, lineHeight = 30.tsp, color = colors.ink), modifier = Modifier.padding(top = Spacing.xxs))
                    Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        d.decidedBy?.let { PersonMark(people.markChar(it), people.person(it), size = 18.dp) }
                        Text(d.decidedAt?.let { relativeDay(it.atZone(state.zone).toLocalDate(), state.today).first }.orEmpty(),
                            style = type.caption.copy(color = colors.muted), modifier = Modifier.weight(1f))
                        TextAction("重新考虑", vm::reopen, color = colors.muted)
                    }
                }
            }

            // ── 备选 ──
            SectionLabel("备选", modifier = Modifier.padding(top = Spacing.l))
            if (d.options.isEmpty()) Text("还没有备选。", style = type.caption.copy(color = colors.muted))
            d.options.forEach { option ->
                val chosen = option == d.finalChoice
                Text(
                    (if (chosen) "✓  " else "·  ") + option,
                    style = type.bodyLarge.copy(color = if (chosen) colors.accent else colors.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .combinedClickable(role = Role.Button, onClickLabel = "选这个", onLongClickLabel = "删掉这个备选",
                            onClick = { if (d.finalChoice == null) choosing = option }, onLongClick = { removing = option })
                        .padding(top = 10.dp),
                )
            }
            if (d.options.size < Limits.DECISION_OPTIONS_MAX) {
                QuickInput(newOption, { newOption = it.take(Limits.DECISION_OPTION_LENGTH.last) }, placeholder = "加一个备选", actionLabel = "加上",
                    onSubmit = { vm.addOption(newOption); newOption = "" }, modifier = Modifier.padding(top = Spacing.xs))
            }
            if (d.finalChoice == null) {
                Text("点一个备选就是定下它；长按可以删掉。", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.xs))
                TextAction("定下别的……", { deciding = true }, color = colors.muted)
            }

            // ── 关注点 ──
            SectionLabel("各自在意的", modifier = Modifier.padding(top = Spacing.l))
            listOfNotNull(people.me, people.partner).forEach { member ->
                val mine = member.userId == people.myUserId
                val concern = d.concerns.firstOrNull { it.userId == member.userId }?.text
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
                        .then(if (mine) Modifier.clickable(role = Role.Button, onClickLabel = "写我在意的") { editingConcern = true } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    PersonMark(people.markChar(member.userId), people.person(member.userId), size = 22.dp)
                    Text(
                        concern ?: if (mine) "写下你在意的……" else "${member.displayName}还没写",
                        style = type.body.copy(color = if (concern != null) colors.ink else colors.faint),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── 复查 ──
            Column(Modifier.padding(top = Spacing.l)) {
                DateChoice("复查日期", d.reviewDate, state.today) { vm.setReviewDate(it) }
                if (d.reviewDue(state.today)) {
                    Text("到了复查的日子：还合适吗？合适就换个下次复查的日子，或者不再复查。", style = type.caption.copy(color = colors.accent),
                        modifier = Modifier.padding(top = Spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        TextAction("三个月后再看", { vm.setReviewDate(state.today.plusMonths(3)) })
                        TextAction("不再复查", { vm.setReviewDate(null) }, color = colors.muted)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (editingQuestion && d != null) {
        TextDialog("改问题", d.question, Limits.DECISION_QUESTION_LENGTH.last, singleLine = true, onDismiss = { editingQuestion = false }) {
            vm.setQuestion(it); editingQuestion = false
        }
    }
    if (editingConcern && d != null) {
        TextDialog("我在意的", d.concerns.firstOrNull { it.userId == people.myUserId }?.text.orEmpty(), Limits.DECISION_CONCERN_MAX, singleLine = false,
            allowEmpty = true, onDismiss = { editingConcern = false }) { vm.setMyConcern(it); editingConcern = false }
    }
    if (deciding) {
        TextDialog("最后决定", "", Limits.DECISION_CHOICE_LENGTH.last, singleLine = false, onDismiss = { deciding = false }) {
            vm.decide(it); deciding = false
        }
    }
    choosing?.let { option ->
        ConfirmDialog("就定这个？", option, "定下来", onConfirm = { vm.decide(option); choosing = null }, onDismiss = { choosing = null })
    }
    removing?.let { option ->
        ConfirmDialog("删掉这个备选？", option, "删掉", onConfirm = { vm.removeOption(option); removing = null }, onDismiss = { removing = null })
    }
    if (deleting) {
        ConfirmDialog("删除这个决定？", "会进回收站，可以恢复。", "删除", onConfirm = { deleting = false; vm.delete() }, onDismiss = { deleting = false })
    }
}

@Composable
private fun TextDialog(
    heading: String,
    initial: String,
    max: Int,
    singleLine: Boolean,
    allowEmpty: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
        title = { Text(heading, style = type.pageTitle.copy(color = colors.ink)) },
        text = { QichiTextField(text, { text = it.take(max) }, label = heading, singleLine = singleLine) },
        confirmButton = { TextAction("保存", { onSave(text) }, enabled = allowEmpty || text.isNotBlank()) },
        dismissButton = { TextAction("取消", onDismiss, color = colors.muted) },
    )
}
