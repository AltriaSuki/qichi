package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme

/**
 * 一行快速输入（设计稿「一起」页底部的灵感输入）：雾层胶囊、右端 accent 色文字按钮。
 * 回车或点按钮提交；空白时按钮置灰。
 */
@Composable
fun QuickInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    actionLabel: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    /** 长文（留言）：回车换行，最多显示 6 行，只能点按钮发出 */
    multiline: Boolean = false,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val canSubmit = value.isNotBlank()
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(25.dp))
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = type.bodyLarge.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            maxLines = if (multiline) 6 else 4,
            keyboardOptions = if (multiline) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = if (multiline) KeyboardActions.Default else KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
            modifier = Modifier.weight(1f).padding(vertical = 13.dp).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = type.bodyLarge.copy(color = colors.faint))
                inner()
            },
        )
        TextAction(actionLabel, { if (canSubmit) onSubmit() }, enabled = canSubmit)
    }
}
