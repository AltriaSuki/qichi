package app.qichi.feature.qna

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.AiMark
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.decor.WaxSeal
import app.qichi.core.designsystem.component.decor.ruledPaper
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.Answer
import app.qichi.shared.rules.Limits
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun QnaScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: QnaViewModel = hiltViewModel<QnaViewModel, QnaViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        FeatureTopBar(Feature.Qna, onBack)
        Segmented(listOf("今日", "题库"), state.tab.ordinal, { viewModel.tab(QnaTab.entries[it]) })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Spacing.page, end = Spacing.page, top = 10.dp, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            if (state.tab == QnaTab.Today) {
                TodayContent(state, viewModel)
            } else {
                LibraryContent(state, viewModel)
            }
            state.error?.let { Text(it, style = QichiTheme.typography.caption.copy(color = colors.accent)) }
        }
    }
}

private val MD = DateTimeFormatter.ofPattern("MM.dd")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TodayContent(state: QnaUiState, viewModel: QnaViewModel) {
    val round = state.todayRound?.value
    val question = state.todayQuestion?.value
    if (question != null && round != null) {
        QuestionCard(question.text, round.roundDate.format(MD), state.roundNumber)
        // 揭晓：蜡封淡出，两份答案同时淡入（「减少动画」时直接切换）
        Crossfade(
            targetState = state.revealed,
            animationSpec = if (QichiTheme.reduceMotion) snap() else tween(450),
            label = "问答揭晓",
        ) { revealed ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                MyAnswer(state, viewModel)
                if (revealed && state.partnerAnswer != null) {
                    AnswerNote(state.partnerAnswer.value, state.people, rotation = 1f)
                } else {
                    SealedEnvelope(state)
                }
            }
        }
    }
    if (state.yesterdayRound != null && state.yesterdayQuestion != null) {
        Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionLabel("昨日")
            Text(state.yesterdayQuestion.value.text, style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                YesterdayAnswer(state.people.myUserId, state.people, state.yesterdayAnswers.map { it.value }, Modifier.weight(1f))
                YesterdayAnswer(state.people.partner?.userId, state.people, state.yesterdayAnswers.map { it.value }, Modifier.weight(1f))
            }
        }
    }
    SuggestedQuestions(state, viewModel)
}

/** 题目卡：纸色便签、雾蓝胶带、左上一个淡淡的大引号；日期和「第 N 题」贴纸。 */
@Composable
private fun QuestionCard(text: String, date: String, number: Int) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Box {
        Column(
            Modifier.fillMaxWidth().lift(colors, QichiShapes.paper).clip(QichiShapes.paper).background(colors.paper)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                Spacer(Modifier.weight(1f))
                if (number > 0) Sticker("第 $number 题", color = colors.personB, rotation = 4f, fontSizeSp = 15f)
            }
            Text(text, style = type.headline.copy(fontSize = 20.tsp, lineHeight = 32.tsp, color = colors.ink), modifier = Modifier.padding(top = 6.dp))
        }
        // 大引号挂在纸的左上角外沿，不压住日期
        Text(
            "\u201C",
            style = type.reading.copy(fontSize = 90.tsp, lineHeight = 90.tsp, color = colors.accent.copy(alpha = .12f)),
            modifier = Modifier.offset(x = 4.dp, y = (-34).dp).clearAndSetSemantics { },
        )
        Tape(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp), color = colors.personB, width = 70.dp, rotation = -4f)
    }
}

@Composable
private fun MyAnswer(state: QnaUiState, viewModel: QnaViewModel) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val answer = state.myAnswer?.value
    if (answer != null && !state.editing) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            AnswerNote(answer, state.people, rotation = -1f)
            if (!state.confirmed) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    TextAction("修改", viewModel::edit)
                    TextAction("确认回答", viewModel::confirm)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SectionLabel("我的回答")
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(
                value = state.draft,
                onValueChange = viewModel::draft,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.envelopeHeight).lift(colors, shape).clip(shape)
                    .ruledPaper((27 * type.scale).dp, top = 12.dp, margin = false).padding(horizontal = 16.dp, vertical = 12.dp),
                enabled = !state.confirmed,
                textStyle = type.bodyLarge.copy(lineHeight = 27.tsp, color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { field ->
                    Box {
                        if (state.draft.isEmpty()) Text("写下你的回答", style = type.bodyLarge.copy(lineHeight = 27.tsp, color = colors.faint))
                        field()
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                TextAction("保存", viewModel::save, enabled = state.draft.trim().isNotEmpty(), color = colors.muted)
                TextAction("确认回答", viewModel::confirm, enabled = state.draft.trim().isNotEmpty())
            }
        }
    }
}

/** 一份回答是一张便签：作者颜色淡淡衬底、角上一条胶带、微微倾斜。 */
@Composable
private fun AnswerNote(answer: Answer, people: People, rotation: Float) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val tint = people.person(answer.authorId).color()
    val zone = remember { ZoneId.systemDefault() }
    Box(Modifier.rotate(rotation)) {
        Column(
            Modifier.fillMaxWidth().lift(colors).clip(QichiShapes.card).background(colors.card).background(tint.copy(alpha = .1f))
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PersonMark(people.markChar(answer.authorId), people.person(answer.authorId), size = 20.dp)
                Text(people.name(answer.authorId), style = type.caption.copy(fontWeight = FontWeight.W500, color = colors.muted), modifier = Modifier.weight(1f))
                Text(answer.updatedAt.atZone(zone).format(HM), style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
            }
            Text(answer.body, style = type.bodyLarge.copy(lineHeight = 27.tsp, color = colors.ink), modifier = Modifier.padding(top = Spacing.xs))
        }
        Tape(Modifier.align(Alignment.TopEnd).offset(x = (-18).dp, y = (-8).dp), color = tint, width = 48.dp, rotation = 6f)
    }
}

/**
 * 对方的回答没揭晓：一只信封（一道斜的封口线）。对方已经确认时封口上是蜡封，还没写时只有一个虚线的人物标记。
 */
@Composable
private fun SealedEnvelope(state: QnaUiState) {
    val colors = QichiTheme.colors
    val partner = state.people.partner
    val name = partner?.displayName ?: "对方"
    val sealed = state.partnerConfirmed
    val flap = colors.line2
    Box(
        Modifier.fillMaxWidth().height(118.dp).lift(colors, QichiShapes.paper).clip(QichiShapes.paper).background(colors.card)
            .drawBehind {
                val p = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2, 50.dp.toPx())
                    lineTo(size.width, 0f)
                }
                drawPath(p, flap, style = Stroke(1.2.dp.toPx()))
            }
            .semantics { contentDescription = if (sealed) "${name}的回答，还没揭晓" else "${name}还没回答" },
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 22.dp)) {
            if (sealed) {
                WaxSeal(state.people.markChar(partner?.userId), size = 56.dp, color = state.people.person(partner?.userId).color())
            } else {
                PersonMark(state.people.markChar(partner?.userId), state.people.person(partner?.userId), size = 40.dp, hollow = true, modifier = Modifier.padding(top = 8.dp))
            }
        }
        HandNote(
            if (sealed) "两个人都答完，一起拆开" else "等${name}写",
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp),
            fontSizeSp = 17f, rotation = -2f,
        )
        Icon(QichiIcons.Lock, contentDescription = null, tint = colors.muted, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp).size(16.dp))
    }
}

@Composable
private fun YesterdayAnswer(userId: UUID?, people: People, answers: List<Answer>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PersonMark(people.markChar(userId), people.person(userId), hollow = answers.none { it.authorId == userId })
        answers.firstOrNull { it.authorId == userId }?.let {
            Text(it.body, style = QichiTheme.typography.caption.copy(fontSize = 14.tsp, color = QichiTheme.colors.muted))
        }
    }
}

/** 「新的问题」（AI 出的，采纳了才进题库）：一行一题，行之间虚线。 */
@Composable
private fun SuggestedQuestions(state: QnaUiState, viewModel: QnaViewModel, showDelete: Boolean = false) {
    val colors = QichiTheme.colors
    Column(Modifier.padding(top = 4.dp)) {
        SectionLabel("新的问题") { AiMark(size = 12) }
        state.suggested.forEachIndexed { i, local ->
            Row(
                Modifier.fillMaxWidth().then(if (i == 0) Modifier else Modifier.dashedDivider(colors, atTop = true)).heightIn(min = Sizes.listRowTall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(local.value.text, style = QichiTheme.typography.body.copy(color = colors.ink), modifier = Modifier.weight(1f))
                TextAction("采纳", { viewModel.adopt(local.value) })
                if (showDelete) TextAction("移除", { viewModel.delete(local.value) }, color = colors.muted)
            }
        }
        TextAction(if (state.busy) "正在想问题" else "请 AI 出题", viewModel::suggest, enabled = state.online && !state.busy, color = colors.personB)
    }
}

@Composable
private fun LibraryContent(state: QnaUiState, viewModel: QnaViewModel) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var draft by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionLabel("自己出题")
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(Limits.QUESTION_TEXT_LENGTH.last) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowTall).lift(colors, shape).clip(shape).background(colors.card)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = type.bodyLarge.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { field ->
                Box {
                    if (draft.isEmpty()) Text("想问对方什么？", style = type.body.copy(color = colors.faint))
                    field()
                }
            },
        )
        TextAction("加入题库", onClick = {
            if (draft.trim().isNotEmpty()) {
                viewModel.addQuestion(draft)
                draft = ""
            }
        }, enabled = draft.trim().isNotEmpty())
        Column {
            SectionLabel("题库") { Text("${state.adopted.size}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted)) }
            state.adopted.forEachIndexed { i, local ->
                Row(
                    Modifier.fillMaxWidth().then(if (i == 0) Modifier else Modifier.dashedDivider(colors, atTop = true)).heightIn(min = Sizes.listRow),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(local.value.text, style = type.body.copy(color = colors.ink), modifier = Modifier.weight(1f))
                    TextAction("移除", { viewModel.delete(local.value) }, color = colors.muted)
                }
            }
        }
        SuggestedQuestions(state, viewModel, showDelete = true)
    }
}
