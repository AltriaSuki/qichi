package app.qichi.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.IconAction
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.chatDay
import app.qichi.shared.api.Message
import app.qichi.shared.model.MessageKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RESULT_TIME = DateTimeFormatter.ofPattern("HH:mm")

/** 聊天搜索：盖在聊天上的整页。输入即搜，点结果跳回聊天里的那条并高亮。 */
@Composable
internal fun ChatSearch(
    state: SearchState,
    people: People,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Message) -> Unit,
) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val focus = remember { FocusRequester() }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(QichiIcons.Back, contentDescription = "关闭搜索", onClick = onClose)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.ink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "搜索聊天记录" },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) Text("搜索聊天记录", style = type.body.copy(color = colors.faint))
                        inner()
                    },
                )
            }
        }
        val hint = when {
            state.error != null -> state.error
            state.searched && state.results.isEmpty() && !state.loading -> "没有找到"
            state.loading && state.results.isEmpty() -> "正在搜索…"
            else -> null
        }
        if (hint != null) {
            Text(hint, style = type.caption.copy(color = colors.muted), modifier = Modifier.padding(start = 28.dp, top = 12.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp)) {
            itemsIndexed(state.results, key = { _, m -> m.id.toString() }) { index, message ->
                if (index >= state.results.size - 5) LaunchedEffect(state.results.size) { onLoadMore() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "跳到这条消息") { onOpen(message) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (message.kind == MessageKind.Ai) {
                        Text("AI", style = type.numeral.copy(fontSize = 15.tsp, color = colors.personB), modifier = Modifier.padding(top = 2.dp))
                    } else {
                        PersonMark(people.markChar(message.authorId), people.person(message.authorId), size = 22.dp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            highlight(message.body, state.query, colors.accent),
                            style = type.body.copy(color = colors.ink),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val at = message.createdAt.atZone(zone)
                        val (day, _) = chatDay(at.toLocalDate(), today)
                        Text("$day  ${RESULT_TIME.format(at)}", style = type.caption.copy(fontSize = 12.tsp, color = colors.muted))
                    }
                }
            }
        }
    }
}

/** 把搜索词在正文里的每一处标成 accent 色（不分大小写）。 */
internal fun highlight(text: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.W400), i, i + q.length)
            from = i + q.length
        }
    }
}
