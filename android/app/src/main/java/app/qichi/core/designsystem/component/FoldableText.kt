package app.qichi.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.qichi.core.designsystem.QichiTheme

/**
 * 可以展开的文字（P14-01）：超过 [collapsedLines] 行时先显示这么多行，下面一个小字「展开」，点一下看全部、再点收起。
 * 没超出时就是普通文字，不能点。[onLongClick] 给外面的长按菜单留着（文字本身接住了点击，长按也要接住再转出去）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 2,
    onLongClick: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val foldable = overflows || expanded
    Column(
        modifier.then(
            if (foldable) {
                Modifier.combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起" else "展开全文",
                    onClick = { expanded = !expanded },
                    onLongClickLabel = if (onLongClick != null) "更多操作" else null,
                    onLongClick = onLongClick,
                )
            } else {
                Modifier
            },
        ),
    ) {
        Text(
            text,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            // 只在收起时量：展开后当然不再超出
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (foldable) Text(if (expanded) "收起" else "展开", style = style.copy(color = QichiTheme.colors.accent))
    }
}
