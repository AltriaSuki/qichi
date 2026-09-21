package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp

data class TabItem(
    val label: String,
    val selected: Boolean,
    /** 聊天的未读数；为空或 0 不显示 */
    val badge: Int? = null,
)

/**
 * 底部四个文字标签：选中项上方一个 4dp 的 accent 圆点；未读数用 Cormorant 斜体小数字写在右上角。
 * 顶部一条 line 色细线，这是设计里极少数用分隔线的地方。
 */
@Composable
fun QichiTabBar(
    items: List<TabItem>,
    onSelect: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .drawBehind { drawLine(colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
    ) {
        items.forEachIndexed { index, item ->
            val badge = item.badge?.takeIf { it > 0 }
            val description = if (badge != null) "${item.label}，$badge 条未读" else item.label
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.tabBarHeight)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Tab
                        selected = item.selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box {
                    Text(
                        text = item.label,
                        style = type.tab.copy(
                            fontWeight = if (item.selected) FontWeight.W400 else FontWeight.W300,
                            color = if (item.selected) colors.ink else colors.muted,
                        ),
                        // 字距加在每个字后面，左侧补同样的量使文字视觉居中
                        modifier = Modifier.padding(start = 4.5.dp),
                    )
                    if (item.selected) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-10).dp)
                                .size(4.dp)
                                .background(colors.accent, CircleShape),
                        )
                    }
                    if (badge != null) {
                        Text(
                            text = if (badge > 99) "99+" else badge.toString(),
                            style = type.numeral.copy(fontSize = 15.tsp, color = colors.accent),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 14.dp, y = (-9).dp),
                        )
                    }
                }
            }
        }
    }
}

/** 详情页顶部：返回箭头 + 标题 + 右侧图标按钮。 */
@Composable
fun BackBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = QichiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = Sizes.backBarHeight)
            .padding(start = 8.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconAction(icon = QichiIcons.Back, contentDescription = "返回", onClick = onBack, iconSize = 22)
        Text(
            text = title,
            style = QichiTheme.typography.pageTitle.copy(color = colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        actions()
    }
}

/** 44dp 触控区域的图标按钮，默认 ink 色。 */
@Composable
fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Int = 22,
    tint: androidx.compose.ui.graphics.Color = QichiTheme.colors.ink,
) {
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else QichiTheme.colors.faint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** 小标题：12sp、muted、字距 0.34em，右侧可带内容（如「全部」、数字）。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = text,
            style = QichiTheme.typography.sectionLabel.copy(color = QichiTheme.colors.muted),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing()
    }
}

@Composable
internal fun HorizontalGap(width: Int) = Spacer(Modifier.width(width.dp))
