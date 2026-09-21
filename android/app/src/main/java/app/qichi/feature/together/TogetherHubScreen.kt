package app.qichi.feature.together

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.TocRow
import app.qichi.core.designsystem.tsp
import app.qichi.navigation.Page
import app.qichi.navigation.TogetherGroup

/**
 * 「一起」入口页：大标题、三组标签（生活 / 创作 / 回看，点击或方向键切换）、目录。
 * 目录右侧的数字在后续阶段接上数据。
 */
@Composable
fun TogetherHubScreen(
    group: TogetherGroup,
    onGroupChange: (TogetherGroup) -> Unit,
    onOpen: (Page) -> Unit,
    counts: Map<Page, String> = emptyMap(),
) {
    val colors = QichiTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val groups = TogetherGroup.entries
                when (event.key) {
                    Key.DirectionRight -> { onGroupChange(groups[(group.ordinal + 1) % groups.size]); true }
                    Key.DirectionLeft -> { onGroupChange(groups[(group.ordinal + groups.size - 1) % groups.size]); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Text(
            text = "一起",
            style = QichiTheme.typography.hubTitle.copy(color = colors.ink),
            modifier = Modifier
                .padding(start = Spacing.page, end = Spacing.page, top = 56.dp, bottom = 18.dp)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier
                .padding(start = Spacing.page, end = Spacing.page, bottom = 30.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            TogetherGroup.entries.forEach { g ->
                GroupTab(label = g.label, selected = g == group, onClick = { onGroupChange(g) })
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
        ) {
            Page.inGroup(group).forEachIndexed { i, page ->
                TocRow(index = i + 1, title = page.title, trailing = counts[page], onClick = { onOpen(page) })
            }
        }
    }
}

@Composable
private fun GroupTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = 46.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Text(
                text = label,
                style = QichiTheme.typography.body.copy(
                    fontSize = 16.tsp,
                    letterSpacing = 0.26.em,
                    fontWeight = if (selected) FontWeight.W400 else FontWeight.W300,
                    color = if (selected) colors.ink else colors.muted,
                ),
                modifier = Modifier.padding(start = 4.dp),
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-10).dp)
                        .size(4.dp)
                        .background(colors.accent, CircleShape),
                )
            }
        }
    }
}
