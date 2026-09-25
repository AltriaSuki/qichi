package app.qichi.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp

/** 雾层卡片：surface 背景、4dp 圆角、无描边无投影。 */
@Composable
fun MistCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(QichiShapes.card)
            .background(QichiTheme.colors.surface)
            .padding(contentPadding),
        content = content,
    )
}

/** surface 背景的胶囊按钮，如心情回应「我在这里」。[selected] 时文字用 accent 色（如已经给出的回应）。 */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val colors = QichiTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Sizes.touchTarget)
            .clip(QichiShapes.pill)
            .background(if (selected) colors.accent.copy(alpha = .1f) else colors.surface)
            .border(1.dp, if (selected) colors.accent.copy(alpha = .3f) else colors.ink.copy(alpha = .06f), QichiShapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = QichiTheme.typography.body.copy(
                fontSize = 14.tsp,
                fontWeight = FontWeight.W500,
                color = when {
                    !enabled -> colors.faint
                    selected -> colors.accent
                    else -> colors.ink
                },
            ),
        )
    }
}

/** 单选胶囊（如情绪选择）：选中时 surface 底 + accent 字，未选中透明底 + muted 字。 */
@Composable
fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QichiTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Sizes.buttonHeight)
            .clip(QichiShapes.pill)
            .background(if (selected) colors.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = QichiTheme.typography.body.copy(
                fontSize = 17.tsp,
                letterSpacing = 0.16.em,
                fontWeight = if (selected) FontWeight.W400 else FontWeight.W300,
                color = if (selected) colors.accent else colors.muted,
            ),
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** 主按钮：ink 底、background 色字，胶囊形，如心情页的「记下」。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = QichiTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = Sizes.buttonHeight)
            .clip(QichiShapes.pill)
            .background(if (enabled) colors.ink else colors.line2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = QichiTheme.typography.body.copy(
                fontSize = 15.tsp,
                fontWeight = FontWeight.W400,
                letterSpacing = 0.16.em,
                color = colors.background,
            ),
            modifier = Modifier.padding(start = 2.4.dp),
        )
    }
}

/** 无背景的文字按钮，默认 accent 色，如「采纳」「完成」；「问 AI」用 personB 色。 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = QichiTheme.colors.accent,
) {
    Box(
        modifier = modifier
            .heightIn(min = Sizes.touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = QichiTheme.typography.body.copy(
                fontSize = 14.tsp,
                fontWeight = FontWeight.W400,
                letterSpacing = 0.1.em,
                color = if (enabled) color else QichiTheme.colors.faint,
            ),
        )
    }
}

/**
 * 圆形复选框：未完成是一圈 faint 细线，完成后 personA 实心 + 白勾。
 * 触控区域至少 44dp（外层留白），视觉大小 [size]（常用 20，子任务 16）。
 */
@Composable
fun CheckCircle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    val colors = QichiTheme.colors
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .then(toggle)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.personA),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = QichiIcons.Check,
                    contentDescription = null,
                    tint = colors.onPerson,
                    modifier = Modifier.size(size * 0.7f),
                )
            }
        } else {
            Box(
                Modifier
                    .size(size)
                    .border(1.dp, colors.faint, CircleShape),
            )
        }
    }
}

/**
 * 带开关的设置行：整行可点，读屏读作「开关」。
 * 开关本身是细线胶囊 + 圆点；开启时 ink 实心，关闭时 line2 细线。
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowTall)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = type.bodyLarge.copy(color = colors.ink))
            if (description != null) Text(description, style = type.caption.copy(color = colors.muted))
        }
        Spacer(Modifier.width(16.dp))
        val knob by animateDpAsState(
            targetValue = if (checked) 18.dp else 0.dp,
            animationSpec = if (QichiTheme.reduceMotion) snap() else tween(180),
            label = "switch",
        )
        Box(
            Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(QichiShapes.pill)
                .background(if (checked) colors.ink else Color.Transparent)
                .border(1.dp, if (checked) colors.ink else colors.line2, QichiShapes.pill)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .offset(x = knob)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (checked) colors.background else colors.faint),
            )
        }
    }
}
