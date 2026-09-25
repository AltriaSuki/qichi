package app.qichi.feature.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qichi.core.data.People
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sky
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FogSeaHero
import app.qichi.core.designsystem.component.HeroLayout
import app.qichi.core.designsystem.component.IntensityDots
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.color
import app.qichi.core.designsystem.component.decor.HandNote
import app.qichi.core.designsystem.component.decor.Illustration
import app.qichi.core.designsystem.component.decor.Postmark
import app.qichi.core.designsystem.component.decor.Scene
import app.qichi.core.designsystem.component.decor.Seal
import app.qichi.core.designsystem.component.decor.Sprig
import app.qichi.core.designsystem.component.decor.Stamp
import app.qichi.core.designsystem.component.decor.Sticker
import app.qichi.core.designsystem.component.decor.Tape
import app.qichi.core.designsystem.component.topBarInset
import app.qichi.core.designsystem.icon.QichiIcons
import app.qichi.core.designsystem.lift
import app.qichi.core.designsystem.tsp
import app.qichi.core.ui.PlanCover
import app.qichi.core.ui.StageTrack
import app.qichi.core.ui.TodoRow
import app.qichi.core.ui.displayName
import app.qichi.core.ui.feelingWord
import app.qichi.core.ui.icon
import app.qichi.navigation.Page
import app.qichi.shared.api.Mood
import app.qichi.shared.model.MoodReplyKind
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

private val chineseMonths = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val postmarkDate = DateTimeFormatter.ofPattern("yy.MM.dd")
private val footDate = DateTimeFormatter.ofPattern("MM.dd")

private fun weekdayName(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}

/** 左上角那句手写问候，随天色变。 */
internal fun greeting(sky: Sky) = when (sky) {
    Sky.Dawn -> "早安"
    Sky.Day -> "午后好"
    Sky.Dusk -> "黄昏了"
    Sky.Night -> "晚安"
}


/**
 * 今天页（按 New-Today）：插画上叠手写问候、两人标记、日期大字和印章；
 * 下面是心情两张卡、今日问答（胶带便签）、待办、安排、进行中、该复查的决定、一年前的今天（邮票 + 邮戳）。
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
                // 插画铺到状态栏下面；往下滚过插画后，状态栏那一条盖上底色（见最下面）
                .verticalScroll(scroll)
                .padding(bottom = 36.dp),
        ) {
            Header(state, viewModel)
            Column(Modifier.padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(Spacing.todaySection)) {
                if (state.partnerMood != null || state.myMood != null) MoodSection(state, onOpen, viewModel::toggleReply)
                QnaCard(state, onOpen)
                TodoSection(state, onOpen, viewModel::toggleTodo)
                ScheduleSection(state, onOpen)
                PlanSection(state, viewModel.urls, onOpenPlan)
                if (state.reviews.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = Spacing.page)) {
                        SectionLabel("该复查了", Feature.Decisions)
                        state.reviews.forEach { d ->
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenDecision(d.id) }.padding(vertical = Spacing.xs)) {
                                Text(d.question, style = type.bodyLarge.copy(color = colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("当时定了：${d.finalChoice}", style = type.caption.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                YearAgoSection(state, viewModel, onOpenDecision, onOpenMessage)
                // 页脚：一枝绿萝和今天的日期
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sprig(width = 110.dp, alpha = .9f)
                    Text("— ${state.today.format(footDate)} —", style = type.numeral.copy(fontSize = 12.tsp, color = colors.faint))
                }
            }
        }
        // 滚过插画后，状态栏那一条盖上底色，状态栏图标不压在文字上
        val heroPx = with(density) { (HERO_HEIGHT - 60.dp).roundToPx() }
        // 只在越过的那一刻变化，滚动时不每一帧都重组
        val pastHero by remember { derivedStateOf { scroll.value > heroPx } }
        if (pastHero) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(colors.background))
        }
    }
}

private val HERO_HEIGHT = 430.dp

/** 插画（或房间照片）+ 手写问候、两人标记、日期大字、印章。 */
@Composable
private fun Header(state: TodayState, viewModel: TodayViewModel) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        val hero = people.room?.heroFileId
        FogSeaHero(Modifier.fillMaxSize(), layout = HeroLayout.Wide)
        if (hero != null) {
            // 照片还没加载出来、或离线读不到时仍是插画
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(viewModel.urls.thumbnail(hero)).crossfade(!QichiTheme.reduceMotion).build(),
                contentDescription = "主视觉照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 照片上下各压一层底色，字看得清，也和下面的内容接得上
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.55f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.page, end = Spacing.page, top = topBarInset()).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HandNote(greeting(QichiTheme.sky), Modifier.weight(1f), fontSizeSp = 26f, color = colors.ink, rotation = -5f)
            val marks = listOfNotNull(people.room?.createdBy?.let { people.members.firstOrNull { m -> m.userId == it } }, people.members.firstOrNull { it.userId != people.room?.createdBy })
                .map { people.markChar(it.userId) to people.person(it.userId) }
            if (marks.isNotEmpty()) PersonMarks(marks, size = 26.dp)
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 22.dp, end = Spacing.page, bottom = Spacing.m)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${state.today.year} 年 ${state.today.monthValue} 月 ${state.today.dayOfMonth} 日，${weekdayName(state.today.dayOfWeek)}"
                },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                state.today.dayOfMonth.toString(),
                style = type.dateDisplay.copy(color = colors.ink),
                // 行高 0.76：字身上方多出的空白裁掉，数字底边和右边两行字对齐
                modifier = Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    chineseMonths[state.today.monthValue - 1],
                    style = type.headline.copy(fontSize = 22.tsp, lineHeight = 28.6.tsp, fontWeight = FontWeight.W600, color = colors.ink),
                    modifier = Modifier.semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${weekdayName(state.today.dayOfWeek)} · ", style = type.caption.copy(fontSize = 14.tsp, color = colors.muted))
                    Text(state.today.year.toString(), style = type.numeral.copy(fontSize = 14.tsp, color = colors.muted))
                }
            }
            Seal("栖迟", Modifier.padding(bottom = 6.dp), size = 40.dp, rotation = -8f)
        }
    }
}

/** 心情：两张卡（对方、我），每张有图标、词、强度数字和十个点；对方需要安慰时贴一张贴纸。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodSection(state: TodayState, onOpen: (Page) -> Unit, onReply: (MoodReplyKind) -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val partner = state.partnerMood?.value
    val mine = state.myMood?.value
    Column(Modifier.padding(horizontal = Spacing.page)) {
        SectionLabel("心情", Feature.Mood)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            partner?.let { MoodCard(it, people.name(it.authorId), people, Modifier.weight(1f)) { onOpen(Page.Mood) } }
            mine?.let { MoodCard(it, "我", people, Modifier.weight(1f)) { onOpen(Page.Mood) } }
            // 只有一个人记了：另一半留空，卡片不被拉满整行
            if (partner == null || mine == null) Spacer(Modifier.weight(1f))
        }
        partner?.let { mood ->
            mood.note?.let {
                Row(Modifier.padding(top = Spacing.sm, bottom = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 18.dp, modifier = Modifier.padding(top = 4.dp))
                    Text("“$it”", style = type.body.copy(color = colors.muted))
                }
            } ?: Spacer(Modifier.height(Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoodReplyKind.entries.forEach { kind ->
                    Pill(kind.displayName, onClick = { onReply(kind) }, selected = state.myRepliesToPartner.any { it.kind == kind })
                }
            }
        }
    }
}

@Composable
private fun MoodCard(mood: Mood, name: String, people: People, modifier: Modifier, onClick: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val tint = people.person(mood.authorId).color()
    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .lift(colors)
                .clip(QichiShapes.card)
                .background(colors.card)
                .background(tint.copy(alpha = .12f))
                .clickable(role = Role.Button, onClickLabel = "打开心情", onClick = onClick)
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 20.dp)
                Text(name, style = type.caption.copy(fontWeight = FontWeight.W500, color = colors.muted), maxLines = 1, modifier = Modifier.weight(1f))
                Icon(mood.label.icon, contentDescription = mood.label.displayName, tint = tint, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    feelingWord(mood.label, mood.intensity),
                    style = type.headline.copy(fontSize = 26.tsp, lineHeight = 35.tsp, color = colors.ink),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 15.tsp, color = colors.muted), modifier = Modifier.padding(bottom = 6.dp))
            }
            IntensityDots(mood.intensity, tint, Modifier.padding(top = 8.dp))
        }
        if (mood.needsComfort && mood.authorId != people.myUserId) {
            Sticker("需要安慰", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-12).dp), rotation = 6f)
        }
    }
}

/** 今日问答：纸色便签、一条胶带、右上角一个淡淡的大引号。 */
@Composable
private fun QnaCard(state: TodayState, onOpen: (Page) -> Unit) {
    val question = state.question ?: return
    val round = state.round ?: return
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val me = people.myUserId
    val label = when {
        round.revealedAt != null -> "看回答"
        me != null && me in round.confirmedBy -> "我的回答"
        else -> "去回答"
    }
    Box(Modifier.padding(horizontal = Spacing.m)) {
        Column(
            Modifier
                .fillMaxWidth()
                .lift(colors, QichiShapes.paper)
                .clip(QichiShapes.paper)
                .background(colors.paper)
                .clickable(role = Role.Button, onClickLabel = label) { onOpen(Page.Qna) }
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(QichiIcons.Qna, contentDescription = null, tint = colors.personB, modifier = Modifier.size(15.dp))
                Text("今日问答", style = type.sectionLabel.copy(fontSize = 12.tsp, color = colors.muted))
            }
            Text(question.text, style = type.headline.copy(fontSize = 19.tsp, lineHeight = 30.4.tsp, color = colors.ink), modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.heightIn(min = 44.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val both = listOfNotNull(people.me, people.partner)
                both.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.width(16.dp).height(1.dp).background(colors.line2))
                    PersonMark(people.markChar(m.userId), people.person(m.userId), size = 20.dp, hollow = m.userId !in round.confirmedBy)
                }
                val partner = people.partner
                if (partner != null && partner.userId !in round.confirmedBy && round.revealedAt == null) {
                    HandNote("等${partner.displayName}", fontSizeSp = 17f, rotation = -3f)
                }
                Spacer(Modifier.weight(1f))
                Text(label, style = type.button.copy(letterSpacing = 0.em, color = colors.accent))
            }
        }
        Tape(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp), color = colors.personA, width = 70.dp, rotation = -3f)
        Text(
            "”",
            style = type.reading.copy(fontSize = 96.tsp, lineHeight = 96.tsp, color = colors.accent.copy(alpha = .14f)),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = (-6).dp).semantics { },
        )
    }
}

/** 待办：右边一个小进度圈（今天完成几件 / 共几件）。 */
@Composable
private fun TodoSection(state: TodayState, onOpen: (Page) -> Unit, onToggle: (app.qichi.shared.api.Todo, Boolean) -> Unit) {
    if (state.todos.isEmpty() && state.doneToday.isEmpty()) return
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val done = state.doneToday.size
    val total = done + state.todos.size
    Column(Modifier.padding(horizontal = Spacing.page)) {
        SectionLabel("待办", Feature.Todo) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "完成 $done 件，共 $total 件" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("$done/$total", style = type.numeral.copy(fontSize = 12.tsp, color = colors.muted))
                val track = colors.line2
                val fill = colors.personA
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width * 4f / 36f
                    val r = size.width * 14f / 36f
                    val tl = Offset(center.x - r, center.y - r)
                    val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                    drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
                    if (total > 0 && done > 0) drawArc(fill, -90f, 360f * done / total, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
        (state.todos + state.doneToday).forEach { item ->
            TodoRow(
                item = item, people = state.people, today = state.today, zone = state.zone,
                onToggle = { onToggle(item.value, it) },
                onClick = { onOpen(Page.Todo) },
            )
        }
    }
}

/** 安排：时间 · 点 · 标题，点连成一条点线；已经过去的是空心点、灰字，接下来那一件衬淡玫瑰浮起。 */
@Composable
private fun ScheduleSection(state: TodayState, onOpen: (Page) -> Unit) {
    if (state.events.isEmpty()) return
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    val now = remember(state.today) { Instant.now() }
    val next = state.events.firstOrNull { e -> !e.value.allDay && (e.value.endsAt ?: e.value.startsAt)?.isAfter(now) == true }
    Column(Modifier.padding(horizontal = Spacing.page)) {
        SectionLabel("安排", Feature.Calendar)
        Box {
            // 竖着的点线，穿过每一行的点
            val line = colors.line2
            // 时间列宽 60（随大字放宽）+ 点那一列的一半
            Canvas(Modifier.matchParentSize().padding(start = (60 * type.scale + 10.25f).dp, top = 29.dp, bottom = 29.dp)) {
                drawLine(line, Offset(0.75.dp.toPx(), 0f), Offset(0.75.dp.toPx(), size.height), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 3.dp.toPx())), cap = StrokeCap.Round)
            }
            Column {
                state.events.forEach { item ->
                    val e = item.value
                    val past = !e.allDay && (e.endsAt ?: e.startsAt)?.isBefore(now) == true
                    val highlight = item == next
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .bleed(12.dp)
                            .then(if (highlight) Modifier.lift(colors, shape).clip(shape).background(colors.card).background(colors.personA.copy(alpha = .1f)) else Modifier)
                            .clickable(role = Role.Button) { onOpen(Page.Calendar) }
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val starts = e.startsAt?.atZone(state.zone)
                        val time = if (!e.allDay && starts != null && starts.toLocalDate() == state.today) starts.format(hm) else null
                        val ink = if (past) colors.muted else colors.ink
                        Box(Modifier.width((60 * type.scale).dp)) {
                            if (time != null) {
                                Text(time, style = type.numeral.copy(color = ink), maxLines = 1, softWrap = false)
                            } else {
                                Text("全天", style = type.caption.copy(color = colors.muted))
                            }
                        }
                        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                            if (past) {
                                val bg = colors.background
                                Canvas(Modifier.size(8.dp)) {
                                    drawCircle(bg)
                                    drawCircle(ink, radius = size.minDimension / 2 - 0.65.dp.toPx(), style = Stroke(1.3.dp.toPx()))
                                }
                            } else {
                                Box(Modifier.size(20.dp).background(colors.personA.copy(alpha = .16f), QichiShapes.pill))
                                Box(Modifier.size(10.dp).background(colors.personA, QichiShapes.pill))
                            }
                        }
                        Text(
                            e.title,
                            style = type.bodyLarge.copy(color = ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (e.participantIds.size == 1) {
                            val p = e.participantIds.single()
                            PersonMark(people.markChar(p), people.person(p), size = 18.dp)
                        } else {
                            val both = listOfNotNull(people.me, people.partner).map { people.markChar(it.userId) to people.person(it.userId) }
                            if (both.size == 2) PersonMarks(both, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

/** 进行中：封面小图（插画 + 一条胶带）、标题、阶段、下一步。 */
@Composable
private fun PlanSection(state: TodayState, urls: app.qichi.core.network.FileUrls, onOpenPlan: (UUID) -> Unit) {
    if (state.plans.isEmpty()) return
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    Column(Modifier.padding(horizontal = Spacing.page)) {
        SectionLabel("进行中", Feature.Plan)
        state.plans.forEachIndexed { i, item ->
            val plan = item.plan
            Column(Modifier.padding(top = if (i > 0) Spacing.l else 4.dp).clickable(role = Role.Button) { onOpenPlan(plan.id) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                    Box {
                        PlanCover(plan, urls, Modifier.size(64.dp), RoundedCornerShape(12.dp), thumbWidth = 200)
                        Tape(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp), color = colors.accent, width = 34.dp, rotation = 18f, alpha = .35f)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(plan.title, style = type.headline.copy(fontSize = 18.tsp, lineHeight = 27.tsp, color = colors.ink), modifier = Modifier.weight(1f))
                            PersonMark(people.markChar(plan.ownerId), people.person(plan.ownerId), size = 20.dp)
                        }
                        if (item.stages.isNotEmpty()) StageTrack(item.stages, item.currentStage, onToggle = null)
                    }
                }
                plan.nextStep?.let { step ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("下一步", style = type.sectionLabel.copy(fontSize = 12.tsp, letterSpacing = 0.em, color = colors.accent), modifier = Modifier.padding(top = 3.dp))
                        Text(step, style = type.body.copy(color = colors.ink))
                    }
                }
            }
        }
    }
}

/** 一年前的今天：一张邮票（那天的照片或插画）盖着邮戳，旁边是那天记下的一句。 */
@Composable
private fun YearAgoSection(state: TodayState, viewModel: TodayViewModel, onOpenDecision: (UUID) -> Unit, onOpenMessage: (UUID) -> Unit) {
    if (state.yearAgoMoods.isEmpty() && state.yearAgoIdeas.isEmpty() && state.yearAgoDecisions.isEmpty() && state.yearAgoPhotos.isEmpty()) return
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people
    // 旁边那一句：先心情的话，再灵感，再定下的决定
    val line: Pair<String, UUID?>? = state.yearAgoMoods.firstOrNull()?.let { (it.note ?: feelingWord(it.label, it.intensity)) to it.authorId }
        ?: state.yearAgoIdeas.firstOrNull()?.let { it.body to it.authorId }
        ?: state.yearAgoDecisions.firstOrNull()?.let { "定下：${it.question} → ${it.finalChoice}" to null }
    val photo = state.yearAgoPhotos.firstOrNull()
    Column(Modifier.padding(horizontal = Spacing.page)) {
        SectionLabel("一年前的今天", Feature.Timeline)
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Stamp(
                    Modifier.clickable(enabled = photo != null, role = Role.Button, onClickLabel = "在聊天里看") { photo?.let { onOpenMessage(it.messageId) } },
                    width = 96.dp, height = 108.dp, rotation = -3f,
                ) {
                    if (photo != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(viewModel.urls.thumbnail(photo.file.id, 400)).crossfade(!QichiTheme.reduceMotion).build(),
                            contentDescription = "一年前的照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Illustration(Scene.Shelf, Modifier.fillMaxSize())
                    }
                }
                Postmark("栖迟", state.yearAgo.format(postmarkDate), Modifier.offset(x = 64.dp, y = (-6).dp), size = 60.dp, rotation = -16f)
            }
            Column(Modifier.weight(1f).padding(start = Spacing.l, top = 24.dp)) {
                line?.let { (text, who) ->
                    Text(text, style = type.bodyLarge.copy(lineHeight = 25.6.tsp, color = colors.ink), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (who != null) PersonMark(people.markChar(who), people.person(who), size = 18.dp)
                        HandNote((who?.let { people.name(it) + " · " } ?: "") + "去年今天", fontSizeSp = 18f, rotation = -2f)
                    }
                }
            }
        }
        // 那天的其它记录和照片
        val restMoods = state.yearAgoMoods.drop(if (line != null && state.yearAgoMoods.isNotEmpty()) 1 else 0)
        val restIdeas = state.yearAgoIdeas.drop(if (state.yearAgoMoods.isEmpty() && state.yearAgoIdeas.isNotEmpty()) 1 else 0)
        restMoods.forEach { mood ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 18.dp)
                Text(mood.note ?: feelingWord(mood.label, mood.intensity), style = type.body.copy(color = colors.ink))
            }
        }
        restIdeas.forEach { idea ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonMark(people.markChar(idea.authorId), people.person(idea.authorId), size = 18.dp)
                Text(idea.body, style = type.body.copy(color = colors.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        state.yearAgoDecisions.drop(if (line?.second == null && state.yearAgoMoods.isEmpty() && state.yearAgoIdeas.isEmpty()) 1 else 0).forEach { d ->
            Text("定下：${d.question} → ${d.finalChoice}", style = type.body.copy(color = colors.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button) { onOpenDecision(d.id) })
        }
        if (state.yearAgoPhotos.size > 1) {
            Row(Modifier.padding(top = Spacing.s).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.yearAgoPhotos.drop(1).forEach { p ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(viewModel.urls.thumbnail(p.file.id, 400)).crossfade(!QichiTheme.reduceMotion).build(),
                        contentDescription = "一年前的照片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(QichiShapes.paper).background(colors.surface)
                            .clickable(role = Role.Button, onClickLabel = "在聊天里看") { onOpenMessage(p.messageId) },
                    )
                }
            }
        }
    }
}

/** 左右各伸出 [by]（设计稿里的负外边距）：浮起的那一行比内容宽一点，里面的字仍和上下对齐。 */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, p.height) { p.place(-by.roundToPx(), 0) }
}
