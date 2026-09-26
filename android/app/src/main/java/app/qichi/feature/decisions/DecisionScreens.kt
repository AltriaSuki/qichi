package app.qichi.feature.decisions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.Fab
import app.qichi.core.designsystem.component.FabClearance
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.MenuAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.sync.Local
import app.qichi.core.ui.DateChoice
import app.qichi.core.ui.dotDate
import app.qichi.core.ui.relativeDay
import app.qichi.shared.api.Decision
import app.qichi.shared.rules.Limits
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            FeatureTopBar(Feature.Decisions, onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.cardPage, end = Spacing.cardPage, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.ml),
            ) {
                if (state.loaded && state.open.isEmpty() && state.decided.isEmpty()) {
                    Text("还没有决定记录。要一起拿主意的事，可以把备选和各自在意的地方写下来，慢慢商量。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(top = Spacing.m))
                    TextAction("记一个", { creating = true })
                }
                (state.open + state.decided).forEachIndexed { i, it -> DecisionCard(it, state.people, state.today, i) { onOpen(it.value.id) } }
                Spacer(Modifier.height(FabClearance))
            }
        }
        Fab("新决定", { creating = true })
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

private val MD = DateTimeFormatter.ofPattern("MM.dd")

/**
 * 一个决定是一张卡片（按 New-Decision-List）：问题、小字（何时提出 / 何时回头看看）；
 * 定了的右上角盖「定」章，没定的贴「还在想」；下面两人标记 + 现在的状态。
 */
@Composable
private fun DecisionCard(local: Local<Decision>, people: People, today: LocalDate, index: Int, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val d = local.value
    val done = d.finalChoice != null
    val rotation = listOf(-.5f, .6f, -.3f, .4f)[index % 4]
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.rotate(rotation).fillMaxWidth().lift(colors, shape).clip(shape).background(colors.card)
            .clickable(role = Role.Button, onClickLabel = "打开", onClick = onClick)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(d.question, style = type.headline.copy(lineHeight = 25.5.tsp, color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = when {
                    d.reviewDate != null && done -> (if (d.reviewDate!!.year == today.year) d.reviewDate!!.format(MD) else dotDate(d.reviewDate!!, withYear = true)) + (if (!d.reviewDate!!.isAfter(today)) " 该回头看看了" else " 回头看看")
                    else -> d.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(MD) + " 提出"
                }
                Text(meta, style = type.caption.copy(color = if (done && d.reviewDate?.let { !it.isAfter(today) } == true) colors.accent else colors.muted), modifier = Modifier.padding(top = 4.dp))
            }
            if (done) Seal("定", size = 30.dp, rotation = 10f) else Sticker("还在想", color = colors.personB, rotation = -5f, fontSizeSp = 15f)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val both = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
            if (both.isNotEmpty()) PersonMarks(both, size = 18.dp)
            Text(
                when {
                    done -> "选了：${d.finalChoice}"
                    d.concerns.size >= 2 -> "两个人各自写了在意的事"
                    d.options.isNotEmpty() -> "${d.options.size} 个备选"
                    else -> "还没有备选"
                },
                style = type.caption.copy(fontSize = 12.tsp, fontWeight = FontWeight.W500, color = if (done) colors.accent else colors.personB),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
        ItemTopBar(d?.question.orEmpty(), onBack, feature = Feature.Decisions, menu = listOf(
            MenuAction("改问题", { editingQuestion = true }),
            MenuAction("删除", { deleting = true }, danger = true),
        ))
        if (d == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.page)) {
            Box(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                Text("？", style = type.reading.copy(fontSize = 120.tsp, lineHeight = 120.tsp, color = colors.accent.copy(alpha = .1f)),
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-30).dp).clearAndSetSemantics { })
                Column {
                    Text(d.question, style = type.headline.copy(fontSize = 22.tsp, lineHeight = 33.tsp, color = colors.ink),
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "改问题") { editingQuestion = true }.semantics { heading() })
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.createdAt.atZone(state.zone).toLocalDate().format(MD), style = type.numeral.copy(fontSize = 13.tsp, color = colors.muted))
                        Text(" 提出", style = type.caption.copy(color = colors.muted))
                        if (d.finalChoice != null) {
                            Text(" · 定于 ", style = type.caption.copy(color = colors.muted))
                            Text(d.decidedAt?.atZone(state.zone)?.toLocalDate()?.format(MD).orEmpty(), style = type.numeral.copy(fontSize = 13.tsp, color = colors.muted))
                            d.decidedBy?.let { PersonMark(people.markChar(it), people.person(it), size = 16.dp, modifier = Modifier.padding(start = 6.dp)) }
                            Spacer(Modifier.weight(1f))
                            TextAction("重新考虑", vm::reopen, color = colors.muted)
                        }
                    }
                }
            }

            // ── 备选：卡片，定下的那张衬暮玫瑰、盖「定了」章 ──
            SectionLabel("备选", Modifier.padding(top = Spacing.l), icon = QichiIcons.BulletList, tint = colors.accent)
            if (d.options.isEmpty() && d.finalChoice == null) Text("还没有备选。", style = type.caption.copy(color = colors.muted))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val shown = if (d.finalChoice != null && d.finalChoice !in d.options) d.options + d.finalChoice!! else d.options
                shown.forEachIndexed { i, option ->
                    OptionCard(
                        letter = ('A' + i).toString(), text = option, chosen = option == d.finalChoice,
                        onClick = { if (d.finalChoice == null) choosing = option },
                        onLongClick = { if (option in d.options) removing = option },
                    )
                }
            }
            if (d.options.size < Limits.DECISION_OPTIONS_MAX && d.finalChoice == null) {
                QuickInput(newOption, { newOption = it.take(Limits.DECISION_OPTION_LENGTH.last) }, placeholder = "加一个备选", actionLabel = "加上",
                    onSubmit = { vm.addOption(newOption); newOption = "" }, modifier = Modifier.padding(top = Spacing.xs))
            }
            if (d.finalChoice == null) {
                Text("点一个备选就是定下它；长按可以删掉。", style = type.caption.copy(color = colors.faint), modifier = Modifier.padding(top = Spacing.xs))
                TextAction("定下别的……", { deciding = true }, color = colors.muted)
            }

            // ── 各自在意：两张便签 ──
            SectionLabel("各自在意", Modifier.padding(top = Spacing.l), icon = QichiIcons.Heart, tint = colors.personA)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOfNotNull(people.me, people.partner).forEachIndexed { i, member ->
                    val mine = member.userId == people.myUserId
                    val concern = d.concerns.firstOrNull { it.userId == member.userId }?.text
                    val tint = people.person(member.userId).color()
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().rotate(if (i == 0) -1f else 1f).lift(colors, shape).clip(shape).background(colors.card).background(tint.copy(alpha = .1f))
                            .then(if (mine) Modifier.clickable(role = Role.Button, onClickLabel = "写我在意的") { editingConcern = true } else Modifier)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        PersonMark(people.markChar(member.userId), people.person(member.userId), size = 22.dp)
                        Text(
                            concern ?: if (mine) "写下你在意的……" else "${member.displayName}还没写",
                            style = type.body.copy(color = if (concern != null) colors.ink else colors.faint),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // ── 复查 ──
            Column(Modifier.padding(top = Spacing.l)) {
                SectionLabel("复查", icon = QichiIcons.Calendar, tint = colors.personB)
                DateChoice("到时候回头看看这个决定", d.reviewDate, state.today) { vm.setReviewDate(it) }
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

/** 一个备选：字母 + 内容的卡片；定下的衬暮玫瑰、描边，右边盖一枚歪着的「定了」圆章。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionCard(letter: String, text: String, chosen: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).lift(colors, shape).clip(shape).background(colors.card)
                .then(if (chosen) Modifier.background(colors.accent.copy(alpha = .1f)).border(1.3.dp, colors.accent.copy(alpha = .5f), shape) else Modifier)
                .combinedClickable(role = Role.Button, onClickLabel = "选这个", onLongClickLabel = "删掉这个备选", onClick = onClick, onLongClick = onLongClick)
                .padding(start = 14.dp, end = if (chosen) 70.dp else 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(letter, style = type.numeral.copy(fontSize = 15.tsp, fontWeight = FontWeight.W500, color = colors.accent))
            Text(text, style = type.body.copy(lineHeight = 23.tsp, color = colors.ink))
        }
        if (chosen) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(52.dp).rotate(-16f)
                    .border(2.dp, colors.accent.copy(alpha = .75f), CircleShape)
                    .padding(3.dp).border(3.dp, colors.accent.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("定了", style = type.sectionLabel.copy(fontSize = 15.tsp, letterSpacing = 0.1.em, color = colors.accent.copy(alpha = .85f)))
            }
        }
    }
}
