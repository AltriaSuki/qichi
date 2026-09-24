package app.qichi.feature.today

import app.qichi.core.designsystem.component.HeroLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import app.qichi.core.designsystem.QichiShapes
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.ComfortFlag
import app.qichi.core.designsystem.component.FogSeaHero
import app.qichi.core.designsystem.component.MistCard
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.StageTrack
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.displayName
import app.qichi.core.ui.feelingWord
import app.qichi.core.ui.monthRoman
import app.qichi.navigation.Page
import app.qichi.shared.model.MoodReplyKind
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.UUID

private val chineseMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
private val hm = DateTimeFormatter.ofPattern("HH:mm")

private fun weekdayName(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}

/**
 * 今天页，按 Main.dc.html：星期与两人标记、日期大字、主视觉，下面是心情、一问、待办、安排、进行中、该复查的决定、一年前。
 * 没有数据的区块整块不显示，不放空状态说明。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    roomId: UUID,
    onOpen: (Page) -> Unit,
    onOpenPlan: (UUID) -> Unit,
    onOpenDecision: (UUID) -> Unit = {},
    onOpenMessage: (UUID) -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel<TodayViewModel, TodayViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(
        Modifier
            .fillMaxSize()
            // 主视觉铺到状态栏下面；往下滚过主视觉后，状态栏那一条盖上底色（见最下面）
            .verticalScroll(scroll)
            .padding(bottom = 48.dp),
    ) {
        // ── 主视觉（按 Main.dc.html：整幅插画或房间照片，星期与两人标记、日期大字叠在上面）──
        Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
            val hero = state.people.room?.heroFileId
            FogSeaHero(Modifier.fillMaxSize(), layout = HeroLayout.Wide)
            if (hero != null) {
                // 照片还没加载出来、或离线读不到时仍是插画
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(viewModel.urls.thumbnail(hero))
                        .crossfade(!QichiTheme.reduceMotion)
                        .build(),
                    contentDescription = "主视觉照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // 照片上下各压一层底色，字看得清，也和下面的内容接得上
                Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.55f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))))
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.page, end = 24.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    weekdayName(state.today.dayOfWeek),
                    style = type.caption.copy(letterSpacing = 0.36.em, color = colors.ink),
                    modifier = Modifier.weight(1f),
                )
                val marks = listOfNotNull(people.room?.createdBy?.let { people.members.firstOrNull { m -> m.userId == it } }, people.members.firstOrNull { it.userId != people.room?.createdBy })
                    .map { people.markChar(it.userId) to people.person(it.userId) }
                if (marks.isNotEmpty()) PersonMarks(marks, size = 26.dp)
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 22.dp, bottom = 18.dp)
                    // 日期大字是旧式数字，只占字身下方的 x 高度；裁掉字身上方的空白，位置贴近设计稿
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val cut = 40.dp.roundToPx()
                        layout(placeable.width, placeable.height - cut) { placeable.place(0, -cut) }
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${state.today.year} 年 ${state.today.monthValue} 月 ${state.today.dayOfMonth} 日"
                    },
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.today.dayOfMonth.toString(), style = type.dateDisplay.copy(color = colors.ink))
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(
                        chineseMonths[state.today.monthValue - 1],
                        style = type.pageTitle.copy(fontSize = 20.tsp, letterSpacing = 0.3.em, color = colors.ink),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(state.today.year.toString(), style = type.numeral.copy(fontSize = 18.tsp, lineHeight = 23.tsp, color = colors.muted))
                }
            }
        }

        Column(
            Modifier.padding(top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.todaySection),
        ) {
            // ── 心情：对方和自己左右两栏，对方写的几句放在下面 ──
            if (state.partnerMood != null || state.myMood != null) {
                Column(
                    Modifier
                        .padding(horizontal = Spacing.page)
                        .clickable(role = Role.Button) { onOpen(Page.Mood) },
                ) {
                    SectionLabel("心情")
                    val partner = state.partnerMood?.value
                    val mine = state.myMood?.value
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        partner?.let { mood ->
                            MoodColumn(mood.authorId, people.name(mood.authorId), mood, people, Modifier.weight(1f))
                        }
                        if (partner != null && mine != null) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
                        mine?.let { mood ->
                            MoodColumn(mood.authorId, "我", mood, people, Modifier.weight(1f))
                        }
                    }
                    partner?.let { mood ->
                        mood.note?.let {
                            Text("\u201C$it\u201D", style = type.body.copy(fontSize = 15.tsp, lineHeight = 28.tsp, fontWeight = FontWeight.W300, color = colors.muted),
                                modifier = Modifier.padding(top = 20.dp, bottom = 14.dp))
                        } ?: Spacer(Modifier.height(Spacing.m))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MoodReplyKind.entries.forEach { kind ->
                                Pill(kind.displayName, onClick = { viewModel.toggleReply(kind) }, selected = state.myRepliesToPartner.any { it.kind == kind })
                            }
                        }
                    }
                }
            }

            // ── 一问 ──
            val question = state.question
            val round = state.round
            if (question != null && round != null) {
                val me = people.myUserId
                val label = when {
                    round.revealedAt != null -> "看回答"
                    me != null && me in round.confirmedBy -> "我的回答"
                    else -> "去回答"
                }
                MistCard(
                    modifier = Modifier
                        .padding(horizontal = Spacing.m)
                        .clickable(role = Role.Button, onClickLabel = label) { onOpen(Page.Qna) },
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 26.dp, bottom = Spacing.m),
                ) {
                    Box {
                        Text(
                            "\u201C",
                            style = type.numeral.copy(fontSize = 72.tsp, lineHeight = 72.tsp, fontStyle = FontStyle.Normal, color = colors.accent),
                            modifier = Modifier.offset(x = (-6).dp, y = (-20).dp),
                        )
                        Text(
                            question.text,
                            style = type.body.copy(fontSize = 21.tsp, lineHeight = 39.tsp, fontWeight = FontWeight.W300, letterSpacing = 0.04.em, color = colors.ink),
                            modifier = Modifier.padding(top = 30.dp),
                        )
                    }
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        val both = listOfNotNull(people.me, people.partner)
                        both.forEachIndexed { i, m ->
                            if (i > 0) Box(Modifier.width(18.dp).height(1.dp).background(colors.line2))
                            PersonMark(people.markChar(m.userId), people.person(m.userId), size = 20.dp, hollow = m.userId !in round.confirmedBy)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            label,
                            style = type.body.copy(fontSize = 14.tsp, letterSpacing = 0.1.em, color = colors.accent),
                            modifier = Modifier.heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }

            // ── 待办 ──
            if (state.todos.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.page)) {
                    SectionLabel("待办")
                    state.todos.forEach { item ->
                        TodoRow(
                            item = item, people = people, today = state.today, zone = state.zone,
                            onToggle = { viewModel.toggleTodo(item.value, it) },
                            onClick = { onOpen(Page.Todo) },
                        )
                    }
                }
            }

            // ── 安排 ──
            if (state.events.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.page)) {
                    SectionLabel("安排")
                    state.events.forEach { item ->
                        val e = item.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.Button) { onOpen(Page.Calendar) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                        ) {
                            val starts = e.startsAt?.atZone(state.zone)
                            val time = when {
                                e.allDay -> null
                                starts != null && starts.toLocalDate() == state.today -> starts.format(hm)
                                else -> null
                            }
                            // 时间列随字号一起放宽，大字模式下标题仍然对齐
                            val timeColumn = Modifier.widthIn(min = (66 * type.scale).dp)
                            if (time != null) {
                                Text(
                                    time,
                                    style = type.numeral.copy(fontSize = 28.tsp, fontWeight = FontWeight.W300, fontStyle = FontStyle.Normal, color = colors.ink),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = timeColumn,
                                )
                            } else {
                                Text("全天", style = type.caption.copy(color = colors.muted), modifier = timeColumn)
                            }
                            Text(e.title, style = type.bodyLarge.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (e.participantIds.size == 1) {
                                val p = e.participantIds.single()
                                PersonMark(people.markChar(p), people.person(p), size = 18.dp)
                            } else {
                                val both = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
                                if (both.size == 2) PersonMarks(both)
                            }
                        }
                    }
                }
            }

            // ── 进行中 ──
            if (state.plans.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.page)) {
                    SectionLabel("进行中")
                    state.plans.forEachIndexed { i, item ->
                        val plan = item.plan
                        Column(
                            Modifier
                                .padding(top = if (i > 0) Spacing.xl else 0.dp)
                                .clickable(role = Role.Button) { onOpenPlan(plan.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                Text(
                                    plan.title,
                                    style = type.body.copy(fontSize = 22.tsp, lineHeight = 30.tsp, fontWeight = FontWeight.W300, letterSpacing = 0.1.em, color = colors.ink),
                                    modifier = Modifier.weight(1f),
                                )
                                PersonMark(people.markChar(plan.ownerId), people.person(plan.ownerId), size = 20.dp)
                            }
                            if (item.stages.isNotEmpty()) StageTrack(item.stages, item.currentStage, onToggle = null)
                            plan.nextStep?.let { step ->
                                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("下一步", style = type.caption.copy(letterSpacing = 0.24.em, color = colors.accent), modifier = Modifier.padding(top = 3.dp))
                                    Text(step, style = type.body.copy(fontSize = 15.tsp, fontWeight = FontWeight.W300, color = colors.ink))
                                }
                            }
                        }
                    }
                }
            }

            // ── 复查 ──
            if (state.reviews.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.page)) {
                    SectionLabel("该复查了")
                    state.reviews.forEach { d ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenDecision(d.id) }.padding(vertical = Spacing.xs)) {
                            Text(d.question, style = type.bodyLarge.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("当时定了：${d.finalChoice}", style = type.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // ── 一年前的今天：心情、灵感、定下的决定、照片 ──
            if (state.yearAgoMoods.isNotEmpty() || state.yearAgoIdeas.isNotEmpty() || state.yearAgoDecisions.isNotEmpty() || state.yearAgoPhotos.isNotEmpty()) {
                Column(Modifier.padding(horizontal = Spacing.page)) {
                    SectionLabel("一年前")
                    val d = state.yearAgo
                    Text("${d.dayOfMonth} · ${monthRoman(d.monthValue)} · ${d.year}", style = type.numeral.copy(fontSize = 18.tsp, lineHeight = 22.tsp, color = colors.muted))
                    state.yearAgoMoods.forEach { mood ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 18.dp)
                            Text(
                                mood.note ?: feelingWord(mood.label, mood.intensity),
                                style = type.body.copy(fontSize = 18.tsp, letterSpacing = 0.04.em, color = colors.ink),
                            )
                        }
                    }
                    state.yearAgoIdeas.forEach { idea ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PersonMark(people.markChar(idea.authorId), people.person(idea.authorId), size = 18.dp)
                            Text(idea.body, style = type.body.copy(fontSize = 18.tsp, letterSpacing = 0.04.em, color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    state.yearAgoDecisions.forEach { d ->
                        Text("定下：${d.question} → ${d.finalChoice}", style = type.body.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button) { onOpenDecision(d.id) })
                    }
                    if (state.yearAgoPhotos.isNotEmpty()) {
                        Row(Modifier.padding(top = Spacing.s).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            state.yearAgoPhotos.forEach { photo ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(viewModel.urls.thumbnail(photo.file.id, 400)).crossfade(!QichiTheme.reduceMotion).build(),
                                    contentDescription = "一年前的照片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(QichiShapes.card).background(colors.surface)
                                        .clickable(role = Role.Button, onClickLabel = "在聊天里看") { onOpenMessage(photo.messageId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // 滚过主视觉后，状态栏那一条盖上底色，状态栏图标不压在文字上
    val heroPx = with(density) { (HERO_HEIGHT - 60.dp).roundToPx() }
    if (scroll.value > heroPx) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(colors.background))
    }
    }
}

private val HERO_HEIGHT = 430.dp

/** 心情的一栏：谁、情绪词和深浅、对方需要安慰时的标记。 */
@Composable
private fun MoodColumn(userId: UUID, name: String, mood: app.qichi.shared.api.Mood, people: app.qichi.core.data.People, modifier: Modifier = Modifier) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonMark(people.markChar(userId), people.person(userId), size = 20.dp)
            Text(name, style = type.caption.copy(letterSpacing = 0.18.em, color = colors.muted), maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(feelingWord(mood.label, mood.intensity), style = type.feeling.copy(fontSize = 30.tsp, lineHeight = 40.tsp, color = colors.ink),
                modifier = Modifier.weight(1f, fill = false))
            Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 22.tsp, color = colors.muted), modifier = Modifier.padding(bottom = 5.dp))
        }
        if (mood.needsComfort && userId != people.myUserId) ComfortFlag()
    }
}
