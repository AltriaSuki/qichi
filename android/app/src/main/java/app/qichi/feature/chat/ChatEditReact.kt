package app.qichi.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.PrimaryButton
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.shared.api.Message
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.rules.Limits
import java.time.Duration
import java.time.Instant
import java.util.UUID

// 聊天的「改文字」和「回应」（P16-05）。

internal val BoardReactionKind.label: String
    get() = when (this) {
        BoardReactionKind.Like -> "喜欢"
        BoardReactionKind.Hug -> "拥抱"
        BoardReactionKind.Support -> "支持"
    }

/** 自己发的、已发出的文字消息，24 小时内能改（与服务端 MessageService.edit 同一条规则）。 */
internal fun canEdit(m: Message, me: UUID?, synced: Boolean, now: Instant = Instant.now()): Boolean =
    synced && me != null && m.authorId == me && m.kind == MessageKind.Text &&
        m.retractedAt == null && m.deletedAt == null && m.body.isNotBlank() &&
        m.createdAt.plus(Duration.ofHours(Limits.MESSAGE_EDIT_HOURS)).isAfter(now)

/** 长按菜单顶上的一排回应：喜欢 / 拥抱 / 支持；再点已选的那个 = 收回。 */
@Composable
internal fun ReactionPicker(mine: BoardReactionKind?, onPick: (BoardReactionKind?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        BoardReactionKind.entries.forEach { kind ->
            Pill(kind.label, onClick = { onPick(if (mine == kind) null else kind) }, selected = mine == kind)
        }
    }
}

/** 气泡下面的小回应：「喜欢 · 拥抱」；两个人给的一样时写「喜欢 ×2」。 */
@Composable
internal fun ReactionChips(reactions: Map<UUID, BoardReactionKind>, people: People, alignEnd: Boolean) {
    if (reactions.isEmpty()) return
    val colors = QichiTheme.colors
    val grouped = reactions.entries.groupBy({ it.value }, { it.key })
    val text = grouped.entries.joinToString(" · ") { (kind, who) -> if (who.size > 1) "${kind.label} ×${who.size}" else kind.label }
    val spoken = reactions.entries.joinToString("，") { (who, kind) -> "${people.name(who)}${kind.label}" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            style = QichiTheme.typography.caption.copy(fontSize = 12.tsp, color = colors.accent),
            modifier = Modifier
                .padding(top = Spacing.xxs)
                .clip(QichiShapes.pill)
                .background(colors.accent.copy(alpha = .08f))
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                .semantics { contentDescription = "回应：$spoken" },
        )
    }
}

/** 改文字：原文预填，改完点「保存」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditMessageSheet(message: Message, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = QichiTheme.colors
    var text by rememberSaveable(message.id) { mutableStateOf(message.body) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl)) {
            QichiTextField(
                text,
                { if (it.length <= Limits.MESSAGE_BODY_MAX) text = it },
                label = "改这条消息",
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Text(
                "改过的消息会标「已编辑」，只能改 ${Limits.MESSAGE_EDIT_HOURS} 小时内发的。",
                style = QichiTheme.typography.caption.copy(color = colors.muted),
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.s),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction("取消", onDismiss, color = colors.muted)
                PrimaryButton(
                    "保存",
                    { onSave(text); onDismiss() },
                    enabled = text.isNotBlank() && text.trim() != message.body,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}
