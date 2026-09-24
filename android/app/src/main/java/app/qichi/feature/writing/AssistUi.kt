package app.qichi.feature.writing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.ui.CharDiff
import app.qichi.shared.model.WriteAssistMode

internal fun WriteAssistMode.label() = when (this) {
    WriteAssistMode.Polish -> "润色"
    WriteAssistMode.Proofread -> "改错别字"
    WriteAssistMode.Shorten -> "缩短"
    WriteAssistMode.Titles -> "帮我起标题"
    WriteAssistMode.Draft -> "AI 起草稿"
}

/** 起标题的结果：每行一个，去掉编号和引号。 */
internal fun titleChoices(result: String): List<String> =
    result.lines().map { it.trim().replace(Regex("^(\\d+[.、)]|[-*·])\\s*"), "").trim('"', '“', '”', '「', '」', '《', '》') }
        .filter { it.isNotEmpty() }.take(5)

/**
 * 写作助手的结果（P9-04 / P9-05）：等的时候显示「正在想…」；
 * 润色 / 改错别字 / 缩短显示逐字对比（删掉的划线、加上的标色），点「用这个」才替换；起标题列出几个，点一个就改名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistSheet(state: AssistState, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).padding(bottom = Spacing.xl)) {
            SectionLabel("AI · ${state.mode.label()}")
            val result = state.result
            when {
                result == null -> {
                    Text("正在想…", style = type.body.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.m))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("取消", onDismiss, color = colors.muted) }
                }
                state.mode == WriteAssistMode.Titles -> {
                    Text("点一个就换成这个标题。", style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.xs))
                    titleChoices(result).forEach { title ->
                        Text(
                            title,
                            style = type.bodyLarge.copy(color = colors.ink),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "用这个标题") { onUse(title) }
                                .padding(vertical = 14.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("不用", onDismiss, color = colors.muted) }
                }
                else -> {
                    Text("删掉的字划了线，加上的字标了颜色。点「用这个」才会换进正文，换了也能撤销。",
                        style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(vertical = Spacing.xs))
                    val diff = buildAnnotatedString {
                        CharDiff.diff(state.original, result).forEach { p ->
                            when (p.kind) {
                                CharDiff.Kind.Same -> append(p.text)
                                CharDiff.Kind.Removed -> withStyle(SpanStyle(color = colors.faint, textDecoration = TextDecoration.LineThrough)) { append(p.text) }
                                CharDiff.Kind.Added -> withStyle(SpanStyle(color = colors.accent, background = colors.accent.copy(alpha = 0.10f))) { append(p.text) }
                            }
                        }
                    }
                    Text(
                        diff,
                        style = type.body.copy(color = colors.ink),
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = Spacing.s),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextAction("不用", onDismiss, color = colors.muted)
                        Spacer(Modifier.padding(start = Spacing.s))
                        PrimaryButton("用这个", { onUse(result) }, enabled = result != state.original)
                    }
                    if (result == state.original) {
                        Text("AI 觉得不用改。", style = type.caption.copy(color = colors.muted))
                    }
                }
            }
        }
    }
}
