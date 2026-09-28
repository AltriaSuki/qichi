package app.qichi.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.ConfirmDialog

/**
 * 有输入框的底部面板（新建、编辑）：写了东西以后（[dirty]），往下滑或点面板外面不会直接关掉、把写的字丢掉，
 * 先问一句「放弃刚才写的？」。没写东西时和普通面板一样；按返回键照常关（那是有意的操作）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftSheet(
    dirty: Boolean,
    onDismiss: () -> Unit,
    containerColor: Color = QichiTheme.colors.background,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDirty by rememberUpdatedState(dirty)
    var asking by remember { mutableStateOf(false) }
    // 面板状态以这个函数为键记住：必须是同一个对象，否则每次重组都会重建面板
    val confirm = remember {
        { target: SheetValue ->
            if (target == SheetValue.Hidden && isDirty) {
                asking = true
                false
            } else {
                true
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm),
        containerColor = containerColor,
        content = content,
    )
    if (asking) {
        ConfirmDialog(
            title = "放弃刚才写的？",
            text = "关掉以后，这次填的内容不会留下。",
            confirmLabel = "放弃",
            onConfirm = onDismiss,
            onDismiss = { asking = false },
            dismissLabel = "继续写",
        )
    }
}
