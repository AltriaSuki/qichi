package app.qichi.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.qichi.core.designsystem.QichiTheme

/** 需要确认的操作（撤回、彻底删除等）：纸色底、宋体，确认键用 accent 色。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
        title = { Text(title, style = type.pageTitle.copy(color = colors.ink)) },
        text = { Text(text, style = type.body.copy(color = colors.muted)) },
        confirmButton = { TextAction(confirmLabel, onClick = { onConfirm(); onDismiss() }) },
        dismissButton = { TextAction("取消", onClick = onDismiss, color = colors.muted) },
    )
}
