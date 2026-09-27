package app.qichi.feature.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ConfirmDialog
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.SwitchRow
import app.qichi.core.designsystem.component.TextAction
import app.qichi.shared.api.ReadingPrompt
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7

/**
 * 选中一段后「问 AI…」（P14-05）：点一条常用提示词直接问，或者当场写一句要求；写的这句可以顺手存成常用。
 * 结果和「解释」一样存在这段旁边，只有自己看得到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskAiSheet(
    selected: String,
    prompts: List<ReadingPrompt>,
    onAsk: (instruction: String) -> Unit,
    onSavePrompt: (ReadingPrompt) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        var wish by rememberSaveable { mutableStateOf("") }
        var keep by rememberSaveable { mutableStateOf(false) }
        val full = prompts.size >= Limits.READING_PROMPTS_MAX
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text("按你的要求问 AI", style = type.headline.copy(color = colors.ink))
            Text("「$selected」", style = type.body.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (prompts.isNotEmpty()) {
                SectionLabel("常用的")
                prompts.forEach { p ->
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "用这条问 AI") { onAsk(p.instruction) }
                            .padding(vertical = Spacing.xs),
                    ) {
                        Text(p.title, style = type.bodyLarge.copy(color = colors.ink))
                        Text(p.instruction, style = type.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            QichiTextField(
                wish, { wish = it.take(Limits.READING_PROMPT_INSTRUCTION_LENGTH.last) },
                label = "写一句要求", placeholder = "比如：翻译成英文；这段和我们最近的生活有什么呼应",
                singleLine = false, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            SwitchRow(
                "存成常用的", keep && !full, { keep = it },
                description = if (full) "常用的已经有 ${Limits.READING_PROMPTS_MAX} 条了，先删掉几条" else "下次选中一段时直接点",
                enabled = !full,
            )
            PrimaryButton(
                "问 AI",
                onClick = {
                    val text = wish.trim()
                    if (keep && !full) onSavePrompt(ReadingPrompt(UuidV7.generate(), promptTitle(text), text))
                    onAsk(text)
                },
                enabled = wish.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextAction("管理常用的提示词", onManage, color = colors.muted)
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

/** 常用提示词：新建、改、删、调顺序（P14-05）。改动马上整套存到账号上，要联网。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptManagerSheet(prompts: List<ReadingPrompt>, onChange: (List<ReadingPrompt>) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var editing by remember { mutableStateOf<ReadingPrompt?>(null) }
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("常用的提示词", style = type.headline.copy(color = colors.ink))
            Text("只有你自己看得到，换手机也还在。选中一段文字后点「问 AI…」就能用。", style = type.caption.copy(color = colors.muted))
            if (prompts.isEmpty()) {
                Text("还没有。可以存几句常用的要求，比如「翻译成英文」「用小学生能懂的话说」。", style = type.caption.copy(color = colors.faint),
                    modifier = Modifier.padding(vertical = Spacing.s))
            }
            prompts.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "修改") { editing = p }.padding(vertical = Spacing.xs)) {
                        Text(p.title, style = type.bodyLarge.copy(color = colors.ink))
                        Text(p.instruction, style = type.caption.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextAction("上移", { onChange(prompts.moved(i, i - 1)) }, enabled = i > 0, color = colors.muted)
                    TextAction("下移", { onChange(prompts.moved(i, i + 1)) }, enabled = i < prompts.lastIndex, color = colors.muted)
                }
            }
            TextAction("新建一条", { creating = true }, enabled = prompts.size < Limits.READING_PROMPTS_MAX)
            Spacer(Modifier.height(Spacing.s))
        }
    }
    if (creating) {
        PromptEditor(initial = null, onSave = { onChange(prompts + it); creating = false }, onDelete = null, onDismiss = { creating = false })
    }
    editing?.let { p ->
        PromptEditor(
            initial = p,
            onSave = { changed -> onChange(prompts.map { if (it.id == p.id) changed else it }); editing = null },
            onDelete = { onChange(prompts.filterNot { it.id == p.id }); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** 新建或修改一条：名字、要求；修改时可以删掉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptEditor(initial: ReadingPrompt?, onSave: (ReadingPrompt) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var instruction by rememberSaveable { mutableStateOf(initial?.instruction.orEmpty()) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = Spacing.page, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            QichiTextField(title, { title = it.take(Limits.READING_PROMPT_TITLE_LENGTH.last) }, label = "名字", placeholder = "比如：翻译")
            QichiTextField(
                instruction, { instruction = it.take(Limits.READING_PROMPT_INSTRUCTION_LENGTH.last) },
                label = "要求", placeholder = "比如：把这段翻译成英文，再说说语气", singleLine = false,
            )
            PrimaryButton(
                "保存",
                onClick = { onSave(ReadingPrompt(initial?.id ?: UuidV7.generate(), title.trim(), instruction.trim())) },
                enabled = title.isNotBlank() && instruction.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (onDelete != null) TextAction("删掉这条", { deleting = true }, color = colors.muted)
            Spacer(Modifier.height(Spacing.s))
        }
    }
    if (deleting && onDelete != null) {
        ConfirmDialog("删掉这条常用提示词？", title, "删掉", onConfirm = { deleting = false; onDelete() }, onDismiss = { deleting = false })
    }
}
