package app.qichi.feature.together

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FeatureTile
import app.qichi.core.designsystem.component.MainTopBar
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.Segmented
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Ribbon
import app.qichi.core.designsystem.component.decor.WaxSeal
import app.qichi.core.designsystem.component.decor.ruledPaper
import app.qichi.core.designsystem.dashedDivider
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.navigation.Page
import app.qichi.navigation.TogetherGroup
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Document
import app.qichi.shared.rules.Limits
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val md = DateTimeFormatter.ofPattern("MM.dd")

/**
 * 「一起」（按 New-Together*）：大标题 + 手写一句、右上两人标记；分段切换生活 / 创作 / 回看（点或方向键）；
 * 每行功能色块 + 名字 + 数量小胶囊。生活组底部是快速记灵感，创作组下面是「最近」两张卡。
 */
@Composable
fun TogetherHubScreen(
    group: TogetherGroup,
    onGroupChange: (TogetherGroup) -> Unit,
    onOpen: (Page) -> Unit,
    counts: Map<Page, String> = emptyMap(),
    people: People = People.Empty,
    recent: RecentCreations = RecentCreations(),
    /** 打开「最近」里的一篇文稿、一个留言主题 */
    onOpenItem: (Page, String) -> Unit = { _, _ -> },
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
        MainTopBar("一起", note = "我们的小日子") {
            val marks = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
            if (marks.isNotEmpty()) PersonMarks(marks, size = 26.dp)
        }
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
                HubRow(page, counts[page], first = i == 0) { onOpen(page) }
            }
            if (group == TogetherGroup.Create && (recent.document != null || recent.topic != null)) {
                RecentSection(recent, people, onOpenItem)
            }
            Spacer(Modifier.size(Spacing.l))
        }
        if (onAddIdea != null && group == TogetherGroup.Life) IdeaCapture(onAddIdea)
    }
}

/** 一行：功能色块、名字、数量小胶囊、细箭头；行之间是虚线。 */
@Composable
private fun HubRow(page: Page, count: String?, first: Boolean, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.dashedDivider(colors, atTop = true))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FeatureTile(page.feature, size = 38.dp)
        Text(page.title, style = type.bodyLarge.copy(fontSize = 17.tsp, fontWeight = FontWeight.W500, color = colors.ink), modifier = Modifier.weight(1f))
        if (count != null) CountPill(count)
        Icon(QichiIcons.ChevronRight, contentDescription = null, tint = colors.faint, modifier = Modifier.size(16.dp))
    }
}

/** 数量小胶囊：数字用等宽字；「需要安慰」是暮玫瑰淡底 + 小圆点。 */
@Composable
private fun CountPill(value: String) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    if (value == NEEDS_COMFORT) {
        Row(
            Modifier.background(colors.accent.copy(alpha = .12f), QichiShapes.pill).padding(horizontal = 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(colors.accent, QichiShapes.pill))
            Text(value, style = type.caption.copy(fontSize = 12.tsp, fontWeight = FontWeight.W500, color = colors.accent))
        }
        return
    }
    val numeric = value.any { it.isDigit() }
    Box(Modifier.background(colors.ink.copy(alpha = .05f), QichiShapes.pill).padding(horizontal = 10.dp, vertical = 1.dp)) {
        Text(value, style = if (numeric) type.numeral.copy(fontSize = 13.tsp, color = colors.muted) else type.caption.copy(color = colors.muted))
    }
}

/** 创作组的「最近」：最近改过的文稿（横线纸 + 书签带，微微左倾）和最近的留言（信封 + 蜡封，微微右倾）。 */
@Composable
private fun RecentSection(recent: RecentCreations, people: People, onOpenItem: (Page, String) -> Unit) {
    Column(Modifier.padding(top = Spacing.ml)) {
        SectionLabel("最近", icon = QichiIcons.Spark, tint = QichiTheme.colors.accent)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(Spacing.ml)) {
            recent.document?.let { DocumentCard(it, people) { onOpenItem(Page.Writing, it.id.toString()) } }
            recent.topic?.let { TopicCard(it, people) { onOpenItem(Page.Board, it.id.toString()) } }
        }
    }
}

@Composable
private fun DocumentCard(doc: Document, people: People, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val line = 26.dp
    Box(Modifier.rotate(-1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .lift(colors, QichiShapes.paper)
                .clip(QichiShapes.paper)
                .ruledPaper(line, top = 18.dp)
                .clickable(role = Role.Button, onClickLabel = "打开文稿", onClick = onClick)
                .padding(start = 54.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Text(doc.title.ifBlank { "没有标题的" }, style = type.headline.copy(fontSize = 16.tsp, lineHeight = line.value.tsp, color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${doc.charCount} 字", style = type.preview.copy(lineHeight = line.value.tsp, color = colors.muted))
            Row(Modifier.heightIn(min = line), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("v${doc.latestVersion}", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                val author = doc.latestAuthorId
                if (author != null && author != people.myUserId) {
                    HandNote("${people.name(author)}刚改过", fontSizeSp = 16f, color = people.person(author).color(), rotation = -2f)
                }
            }
        }
        Ribbon(Modifier.padding(start = 22.dp), height = 40.dp)
    }
}

@Composable
private fun TopicCard(topic: BoardTopic, people: People, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val zone = remember { ZoneId.systemDefault() }
    Row(
        Modifier
            .rotate(1f)
            .fillMaxWidth()
            .lift(colors, QichiShapes.paper)
            .clip(QichiShapes.paper)
            .background(colors.card)
            .clickable(role = Role.Button, onClickLabel = "打开留言", onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        WaxSeal(people.markChar(topic.authorId), size = 44.dp, color = people.person(topic.authorId).color())
        Column(Modifier.weight(1f)) {
            Text(topic.title, style = type.headline.copy(fontSize = 16.tsp, color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(topic.updatedAt.atZone(zone).format(md), style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                Text(" · ${people.name(topic.authorId)}", style = type.caption.copy(color = colors.muted))
            }
        }
        Icon(QichiIcons.ChevronRight, contentDescription = null, tint = colors.faint, modifier = Modifier.size(16.dp))
    }
}

/** 生活组底部：快速记灵感（浮起的胶囊，左边灵感色块，右边「记下」）。 */
@Composable
private fun IdeaCapture(onAddIdea: (String) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var idea by rememberSaveable { mutableStateOf("") }
    val submit = { if (idea.isNotBlank()) { onAddIdea(idea.trim()); idea = "" } }
    val shape = RoundedCornerShape(27.dp)
    Row(
        Modifier
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = Spacing.xs, bottom = Spacing.m)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .lift(colors, shape)
            .clip(shape)
            .background(colors.card)
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FeatureTile(Feature.Ideas, size = 34.dp)
        BasicTextField(
            value = idea,
            onValueChange = { idea = it.take(Limits.IDEA_BODY_LENGTH.last) },
            textStyle = type.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = "记一个念头" },
            decorationBox = { inner ->
                if (idea.isEmpty()) Text("一个念头，可以带 #标签", style = type.body.copy(color = colors.faint))
                inner()
            },
        )
        TextAction("记下", { submit() }, enabled = idea.isNotBlank())
    }
}
