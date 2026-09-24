package app.qichi.feature.together

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.MainTopBar
import app.qichi.core.designsystem.component.QuickInput
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TocRow
import app.qichi.navigation.Page
import app.qichi.navigation.TogetherGroup
import app.qichi.shared.rules.Limits

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
    /** 底部的快速记灵感；为空时不显示 */
    onAddIdea: ((String) -> Unit)? = null,
) {
    val colors = QichiTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
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
        MainTopBar("一起", note = "我们的小日子")
        Segmented(
            items = TogetherGroup.entries.map { it.label },
            selected = group.ordinal,
            onSelect = { onGroupChange(TogetherGroup.entries[it]) },
        )
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
        if (onAddIdea != null) {
            var idea by rememberSaveable { mutableStateOf("") }
            QuickInput(
                value = idea,
                onValueChange = { idea = it.take(Limits.IDEA_BODY_LENGTH.last) },
                placeholder = "记一个灵感",
                actionLabel = "记下",
                onSubmit = { onAddIdea(idea); idea = "" },
                modifier = Modifier.imePadding().padding(start = 20.dp, end = 20.dp, top = Spacing.xs, bottom = Spacing.s),
            )
        }
    }
}
