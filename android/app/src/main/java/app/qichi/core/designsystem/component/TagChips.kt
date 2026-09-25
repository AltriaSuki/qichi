package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.tsp

/** `#标签`：等宽小字，功能色淡底的小圆角标签。 */
@Composable
fun TagChip(tag: String, modifier: Modifier = Modifier, color: Color = QichiTheme.colors.accent) {
    Text(
        "#$tag",
        style = QichiTheme.typography.tag.copy(color = color),
        maxLines = 1,
        modifier = modifier.background(color.copy(alpha = .12f), QichiShapes.pill).padding(horizontal = 7.dp),
    )
}

/**
 * 顶部一排筛选（「全部」+ 各个标签）：可以横着滑，右边渐隐；选中的是深色底。
 * [selected] 为空表示「全部」。
 */
@Composable
fun TagFilterRow(
    tags: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.s),
) {
    Row(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 右边 14% 渐隐，提示还能往右滑
                drawRect(
                    Brush.horizontalGradient(0.86f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        FilterChip("全部", selected == null) { onSelect(null) }
        tags.forEach { t -> FilterChip("#$t", selected == t) { onSelect(t) } }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(QichiShapes.pill)
            .background(if (on) colors.ink else colors.surface)
            .then(if (on) Modifier else Modifier.border(1.dp, colors.ink.copy(alpha = .06f), QichiShapes.pill))
            .selectable(selected = on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = QichiTheme.typography.body.copy(fontSize = 14.tsp, fontWeight = FontWeight.W500, color = if (on) colors.background else colors.ink))
    }
}
