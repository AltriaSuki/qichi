package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.icon.QichiIcons

/**
 * 单行输入框：surface 底色、全圆角胶囊（与聊天输入框一致），上方是小标题，下方是错误提示（accent 色）。
 */
@Composable
fun QichiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    /** 要一打开就把光标放进来时传（新建面板的第一个输入框） */
    focusRequester: FocusRequester? = null,
    /** 告诉系统和密码管理器这是什么（用户名、密码、新密码），好自动填 */
    contentType: ContentType? = null,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    // 密码可以点右边的眼睛看一眼（输错了看不见是哪一位）
    var reveal by remember { mutableStateOf(false) }
    val hidden = password && !reveal
    Column(modifier.fillMaxWidth()) {
        SectionLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.buttonHeight)
                .clip(if (singleLine) QichiShapes.pill else QichiShapes.card)
                .background(colors.surface)
                .padding(start = 18.dp, end = if (password) 4.dp else 18.dp, top = if (singleLine) 0.dp else 12.dp, bottom = if (singleLine) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = type.body.copy(color = colors.muted))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = type.body.copy(fontSize = type.bodyLarge.fontSize, color = colors.ink).let {
                        // 密码的圆点用系统字体，宋体里的圆点太小
                        if (hidden) it.copy(fontFamily = FontFamily.Default, letterSpacing = 0.1.em) else it
                    },
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .semantics {
                            contentDescription = label
                            if (error != null) error(error)
                            if (contentType != null) this.contentType = contentType
                        },
                )
            }
            if (password) {
                IconAction(
                    if (reveal) QichiIcons.EyeOff else QichiIcons.Eye,
                    contentDescription = if (reveal) "隐藏密码" else "显示密码",
                    onClick = { reveal = !reveal },
                    iconSize = 20,
                    tint = colors.muted,
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = type.caption.copy(color = colors.accent),
                modifier = Modifier.padding(start = Spacing.m, top = Spacing.xxs),
            )
        }
    }
}

/** 常用的键盘选项。 */
object QichiKeyboard {
    val username = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)
    val code = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Characters)
}
