package app.qichi.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.marker
import app.qichi.core.designsystem.icon.QichiIcons

/** 顶栏右边的一个图标按钮。 */
data class BarAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 为空用 ink；选中状态（如已加书签）用 accent */
    val tint: androidx.compose.ui.graphics.Color? = null,
)

/** 收在「更多」里的一项。[danger] 用暮玫瑰色字（删除之类）。 */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

/** 功能首页顶栏最多露出的图标数（含「更多」）。 */
const val MAX_FEATURE_BAR_ICONS = 2

/** 单项页顶栏最多露出的图标数（含「更多」）。 */
const val MAX_ITEM_BAR_ICONS = 3

/**
 * 顶栏那一行离屏幕顶端的距离：设计稿是 40dp；状态栏更高时让到状态栏下面 8dp。
 */
@Composable
fun topBarInset(): Dp {
    val status = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    return max(40.dp, status + Spacing.xs)
}

/**
 * 顶栏右边的图标：最多 [max] 个（含「更多」）；放不下的和 [menu] 一起收进「更多」。
 */
@Composable
fun BarActions(actions: List<BarAction>, menu: List<MenuAction> = emptyList(), max: Int = MAX_FEATURE_BAR_ICONS) {
    val overflow = menu.isNotEmpty() || actions.size > max
    val shown = if (overflow) actions.take(max - 1) else actions
    val rest = actions.drop(shown.size).map { MenuAction(it.label, it.onClick, enabled = it.enabled) } + menu
    shown.forEach { IconAction(it.icon, it.label, it.onClick, enabled = it.enabled, tint = it.tint ?: QichiTheme.colors.ink) }
    if (rest.isNotEmpty()) {
        val colors = QichiTheme.colors
        val type = QichiTheme.typography
        var open by remember { mutableStateOf(false) }
        Box {
            IconAction(QichiIcons.More, "更多", { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.paper) {
                rest.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label, style = type.body.copy(color = if (item.danger) colors.accent else colors.ink)) },
                        onClick = { open = false; item.onClick() },
                        enabled = item.enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconAction(icon = QichiIcons.Back, contentDescription = "返回", onClick = onBack)
}

/**
 * 主页（一起、我的；今天和聊天另有形态）顶栏：右上一行（两人标记或图标），
 * 下面大标题（荧光笔）+ 一句手写；右侧角落一枝绿萝。[side] 放在标题行右边（如我的页头像印章），有它就不画绿萝。
 */
@Composable
fun MainTopBar(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    side: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = QichiTheme.colors
    val top = topBarInset()
    Box(modifier.fillMaxWidth()) {
        if (side == null) {
            Sprig(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = top + 50.dp), width = 132.dp, flip = true)
        }
        Column(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, top = top, bottom = Spacing.s)) {
            Row(
                Modifier.fillMaxWidth().height(Sizes.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.End),
            ) { trailing() }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = QichiTheme.typography.largeTitle.copy(color = colors.ink),
                        maxLines = 1,
                        modifier = Modifier.marker(colors.personA).semantics { heading() },
                    )
                    if (note != null) HandNote(note, Modifier.padding(start = Spacing.s, top = Spacing.xs), fontSizeSp = 22f, rotation = -4f)
                }
                side?.invoke()
            }
        }
    }
}

/**
 * 功能首页顶栏：返回（回「一起」）+ 右边最多两个图标；下面功能色块 + 大标题（功能色荧光笔）+ 一句手写。
 */
@Composable
fun FeatureTopBar(
    feature: Feature,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = feature.title,
    note: String? = feature.note,
    actions: List<BarAction> = emptyList(),
    menu: List<MenuAction> = emptyList(),
    /** 色块里的图标，默认是功能的图标（「标签」页用标签图标） */
    icon: ImageVector = feature.icon,
) {
    val colors = QichiTheme.colors
    Column(modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.sm, top = topBarInset(), bottom = Spacing.s)) {
        Row(Modifier.fillMaxWidth().height(Sizes.touchTarget), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            BarActions(actions, menu, MAX_FEATURE_BAR_ICONS)
        }
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            FeatureTile(icon, feature.color, size = 40.dp)
            Text(
                title,
                style = QichiTheme.typography.featureTitle.copy(color = colors.ink),
                maxLines = 1,
                modifier = Modifier.marker(feature.color).semantics { heading() },
            )
            if (note != null) HandNote(note, Modifier.padding(start = Spacing.xxs, top = Spacing.xs), fontSizeSp = 20f, rotation = -4f)
        }
    }
}

/**
 * 单项页顶栏：返回（回功能首页）+ 两行：功能色小字功能名 / 这一项的名字 + 右边图标。
 * [feature] 为空时只有标题一行（登录等不属于任何功能的页）。
 */
@Composable
fun ItemTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    feature: Feature? = null,
    actions: List<BarAction> = emptyList(),
    menu: List<MenuAction> = emptyList(),
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val top = topBarInset()
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, end = Spacing.sm, top = top, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BackButton(onBack)
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            if (feature != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(feature.icon, contentDescription = null, tint = feature.color, modifier = Modifier.size(13.dp))
                    Text(feature.title, style = type.barFeature.copy(color = feature.color))
                }
            }
            Text(
                title,
                style = type.barTitle.copy(color = colors.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        BarActions(actions, menu, MAX_ITEM_BAR_ICONS)
    }
}

/** 功能色块：圆角方块、功能色 15% 底、功能色图标（边长 × 0.56）。 */
@Composable
fun FeatureTile(feature: Feature, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    FeatureTile(feature.icon, feature.color, modifier, size)
}

@Composable
fun FeatureTile(icon: ImageVector, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).background(color.copy(alpha = .15f), QichiShapes.featureTile(size)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * .56f))
    }
}
