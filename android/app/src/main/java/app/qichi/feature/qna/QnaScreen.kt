package app.qichi.feature.qna

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FeatureTopBar
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.QuoteMark
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.WaxSeal
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.Answer
import app.qichi.shared.rules.Limits
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
        QnaTabs(state.tab, viewModel::tab)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Spacing.page, end = Spacing.page, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
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

@Composable
private fun QnaTabs(selected: QnaTab, onSelect: (QnaTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.page), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        QnaTab.entries.forEach { tab ->
            val active = selected == tab
            Box(
                Modifier.heightIn(min = Sizes.listRow).clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { this.selected = active },
                contentAlignment = Alignment.Center,
            ) {
                if (active) Box(Modifier.align(Alignment.TopCenter).size(Spacing.xxs).clip(CircleShape)
                    .background(QichiTheme.colors.accent))
                Text(
                    if (tab == QnaTab.Today) "今日" else "题库",
                    style = QichiTheme.typography.tab.copy(
                        color = if (active) QichiTheme.colors.ink else QichiTheme.colors.muted,
                        fontWeight = if (active) FontWeight.W400 else FontWeight.W300,
                    ),
                    modifier = Modifier.padding(horizontal = Spacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun TodayContent(state: QnaUiState, viewModel: QnaViewModel) {
    val question = state.todayQuestion?.value
    if (question != null) {
        Column(Modifier.fillMaxWidth()) {
            QuoteMark()
            Text(
                question.text,
                style = QichiTheme.typography.question.copy(color = QichiTheme.colors.ink),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Crossfade(
            targetState = state.revealed,
            animationSpec = if (QichiTheme.reduceMotion) snap() else tween(450),
            label = "问答揭晓",
        ) { revealed ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                MyAnswer(state, viewModel)
                if (revealed && state.partnerAnswer != null) {
                    AnswerCard(state.partnerAnswer.value, state.people)
                } else {
                    WaxSeal(state.people.markChar(state.people.partner?.userId), state.people.partner?.displayName ?: "对方")
                }
            }
        }
    }
    if (state.yesterdayRound != null && state.yesterdayQuestion != null) {
        Column(Modifier.padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SectionLabel("昨日")
            Text(state.yesterdayQuestion.value.text,
                style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                YesterdayAnswer(state.people.myUserId, state.people, state.yesterdayAnswers.map { it.value }, Modifier.weight(1f))
                YesterdayAnswer(state.people.partner?.userId, state.people, state.yesterdayAnswers.map { it.value }, Modifier.weight(1f))
            }
        }
    }
    SuggestedQuestions(state, viewModel)
}

@Composable
private fun MyAnswer(state: QnaUiState, viewModel: QnaViewModel) {
    val answer = state.myAnswer?.value
    if (answer != null && !state.editing) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            AnswerCard(answer, state.people)
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
            MistCard {
                BasicTextField(
                    value = state.draft,
                    onValueChange = viewModel::draft,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.envelopeHeight),
                    enabled = !state.confirmed,
                    textStyle = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
                    cursorBrush = SolidColor(QichiTheme.colors.accent),
                    decorationBox = { field ->
                        Box {
                            if (state.draft.isEmpty()) Text("写下你的回答", style = QichiTheme.typography.body.copy(color = QichiTheme.colors.muted))
                            field()
                        }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                TextAction("保存", viewModel::save, enabled = state.draft.trim().isNotEmpty())
                TextAction("确认回答", viewModel::confirm, enabled = state.draft.trim().isNotEmpty())
            }
        }
    }
}

@Composable
private fun AnswerCard(answer: Answer, people: People) {
    MistCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
            PersonMark(people.markChar(answer.authorId), people.person(answer.authorId))
            Text(answer.body, style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
                modifier = Modifier.weight(1f))
        }
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

@Composable
private fun SuggestedQuestions(state: QnaUiState, viewModel: QnaViewModel, showDelete: Boolean = false) {
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SectionLabel("新的问题")
        state.suggested.forEach { local ->
            Row(Modifier.fillMaxWidth().heightIn(min = Sizes.listRowTall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(local.value.text, style = QichiTheme.typography.body.copy(color = QichiTheme.colors.ink),
                    modifier = Modifier.weight(1f))
                TextAction("采纳", { viewModel.adopt(local.value) })
                if (showDelete) TextAction("移除", { viewModel.delete(local.value) }, color = QichiTheme.colors.muted)
            }
        }
        TextAction(if (state.busy) "正在想问题" else "请 AI 出题", viewModel::suggest,
            enabled = state.online && !state.busy, color = QichiTheme.colors.personB)
    }
}

@Composable
private fun LibraryContent(state: QnaUiState, viewModel: QnaViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.detailSection)) {
        SectionLabel("自己出题")
        MistCard {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(Limits.QUESTION_TEXT_LENGTH.last) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.listRowTall),
                textStyle = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
                cursorBrush = SolidColor(QichiTheme.colors.accent),
                decorationBox = { field ->
                    Box {
                        if (draft.isEmpty()) Text("想问对方什么？", style = QichiTheme.typography.body.copy(color = QichiTheme.colors.muted))
                        field()
                    }
                },
            )
        }
        TextAction("加入题库", onClick = {
            if (draft.trim().isNotEmpty()) {
                viewModel.addQuestion(draft)
                draft = ""
            }
        }, enabled = draft.trim().isNotEmpty())
        SectionLabel("题库")
        state.adopted.forEach { local ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(local.value.text,
                    style = QichiTheme.typography.bodyLarge.copy(color = QichiTheme.colors.ink),
                    modifier = Modifier.weight(1f))
                TextAction("移除", { viewModel.delete(local.value) }, color = QichiTheme.colors.muted)
            }
        }
        SuggestedQuestions(state, viewModel, showDelete = true)
    }
}
