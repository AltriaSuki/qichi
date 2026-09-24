package app.qichi.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp

data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    /** 聊天的未读数；为空或 0 不显示 */
    val badge: Int? = null,
)

/**
 * 底部四个标签：图标 + 字；选中项图标下衬 56×30 的淡玫瑰胶囊，字 600；
 * 未读数是暮玫瑰小圆角数字（外面一圈底色）。顶部一条 line 色细线。
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
            .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.xxs, bottom = Spacing.s),
    ) {
        items.forEachIndexed { index, item ->
            val badge = item.badge?.takeIf { it > 0 }
            val description = if (badge != null) "${item.label}，$badge 条未读" else item.label
            val tint = if (item.selected) colors.ink else colors.muted
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.tabBarHeight)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Tab
                        selected = item.selected
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier
                        .size(56.dp, 30.dp)
                        .background(if (item.selected) colors.personA.copy(alpha = .18f) else Color.Transparent, QichiShapes.pill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    if (badge != null) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-4).dp, y = (-5).dp)
                                .background(colors.background, QichiShapes.pill)
                                .padding(2.dp),
                        ) {
                            Box(
                                Modifier
                                    .defaultMinSize(16.dp, 16.dp)
                                    .background(colors.accent, QichiShapes.pill)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (badge > 99) "99+" else badge.toString(),
                                    style = type.numeral.copy(fontSize = 10.tsp, fontWeight = FontWeight.W500, color = colors.background),
                                )
                            }
                        }
                    }
                }
                Text(
                    text = item.label,
                    style = type.tab.copy(fontWeight = if (item.selected) FontWeight.W600 else FontWeight.W500, color = tint),
                )
            }
        }
    }
}

/** 44dp 触控区域的图标按钮，默认 ink 色。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Int = 22,
    tint: Color = QichiTheme.colors.ink,
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

/**
 * 小标题：功能色小图标（可无）+ 13sp 粗体 muted + 一串点线 + 右侧内容（如「全部」、数字）。
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = QichiTheme.colors.accent,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = QichiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            text = text,
            style = QichiTheme.typography.sectionLabel.copy(color = colors.muted),
            modifier = Modifier.semantics { heading() },
        )
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = 1.dp.toPx()
            val step = 6.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                drawCircle(colors.line2, r, Offset(x, size.height / 2))
                x += step
            }
        }
        trailing()
    }
}

/** 带功能色图标的小标题。 */
@Composable
fun SectionLabel(text: String, feature: Feature, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) =
    SectionLabel(text, modifier, feature.icon, feature.color, trailing)

/**
 * 分段切换：浅底胶囊（ink 6%），选中那格是 card 色浮起、字 600。
 */
@Composable
fun Segmented(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    margin: PaddingValues = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.s),
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        modifier
            .padding(margin)
            .fillMaxWidth()
            .background(colors.ink.copy(alpha = .06f), QichiShapes.pill)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .then(if (on) Modifier.shadow(1.5.dp, QichiShapes.pill).background(colors.card, QichiShapes.pill) else Modifier)
                    .clip(QichiShapes.pill)
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = type.body.copy(
                        fontSize = 14.tsp,
                        fontWeight = if (on) FontWeight.W600 else FontWeight.W500,
                        color = if (on) colors.ink else colors.muted,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 新建按钮：右下角深色胶囊（ink 底、底色字、加号），放在页面最外层的 Box 里。
 */
@Composable
fun BoxScope.Fab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = QichiTheme.colors
    Row(
        modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = Spacing.cardPage, bottom = Spacing.xl)
            .lift(colors, QichiShapes.pill, heavy = true)
            .clip(QichiShapes.pill)
            .background(colors.ink)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(start = Spacing.ml, end = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(QichiIcons.Plus, contentDescription = null, tint = colors.background, modifier = Modifier.size(18.dp))
        Text(text, style = QichiTheme.typography.button.copy(color = colors.background))
    }
}

/** 列表最后给新建按钮让出的空白。 */
val FabClearance = 100.dp

@Composable
internal fun HorizontalGap(width: Int) = Spacer(Modifier.width(width.dp))
