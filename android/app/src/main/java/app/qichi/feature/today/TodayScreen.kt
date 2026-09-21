package app.qichi.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.qichi.core.designsystem.component.PersonMark
import app.qichi.core.designsystem.component.PersonMarks
import app.qichi.core.designsystem.component.Pill
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.tsp
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
 * 今天页，按 Main.dc.html：星期与两人标记、日期大字、主视觉，下面是心情、待办、安排、一年前。
 * 没有数据的区块整块不显示，不放空状态说明。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    roomId: UUID,
    onOpen: (Page) -> Unit,
    viewModel: TodayViewModel = hiltViewModel<TodayViewModel, TodayViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    val people = state.people

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // 先让出状态栏再滚动：内容不会滑到状态栏图标下面
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 48.dp),
    ) {
        // ── 日期 ──
        Column(Modifier.padding(start = Spacing.page, end = Spacing.page, top = Spacing.xxxl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    weekdayName(state.today.dayOfWeek),
                    style = type.caption.copy(letterSpacing = 0.34.em, color = colors.muted),
                    modifier = Modifier.weight(1f),
                )
                val marks = listOfNotNull(people.room?.createdBy?.let { people.members.firstOrNull { m -> m.userId == it } }, people.members.firstOrNull { it.userId != people.room?.createdBy })
                    .map { people.markChar(it.userId) to people.person(it.userId) }
                if (marks.isNotEmpty()) PersonMarks(marks, size = 26.dp)
            }
            Row(
                Modifier
                    .padding(top = Spacing.l)
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
                horizontalArrangement = Arrangement.spacedBy(18.dp),
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

        // ── 主视觉：房间设了照片就显示照片（盖在雾海上：照片还没加载出来、或离线读不到时仍是插画）──
        Box(
            Modifier
                .padding(start = 100.dp, top = Spacing.xxl)
                .fillMaxWidth()
                .height(300.dp),
        ) {
            FogSeaHero(Modifier.fillMaxSize())
            state.people.room?.heroFileId?.let { hero ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(viewModel.urls.thumbnail(hero))
                        .crossfade(!QichiTheme.reduceMotion)
                        .build(),
                    contentDescription = "主视觉照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            Modifier.padding(top = Spacing.todaySection),
            verticalArrangement = Arrangement.spacedBy(Spacing.todaySection),
        ) {
            // ── 心情 ──
            if (state.partnerMood != null || state.myMood != null) {
                Column(
                    Modifier
                        .padding(horizontal = Spacing.page)
                        .clickable(role = Role.Button) { onOpen(Page.Mood) },
                ) {
                    SectionLabel("心情")
                    state.partnerMood?.value?.let { mood ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 22.dp)
                            Text(people.name(mood.authorId), style = type.caption.copy(letterSpacing = 0.14.em, color = colors.muted))
                            Spacer(Modifier.weight(1f))
                            if (mood.needsComfort) ComfortFlag()
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(feelingWord(mood.label, mood.intensity), style = type.feeling.copy(fontSize = 32.tsp, lineHeight = 42.tsp, color = colors.ink))
                            Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 24.tsp, color = colors.muted), modifier = Modifier.padding(bottom = 6.dp))
                        }
                        mood.note?.let {
                            Text(it, style = type.body.copy(color = colors.muted), modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        } ?: Spacer(Modifier.height(Spacing.s))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MoodReplyKind.entries.forEach { kind ->
                                Pill(kind.displayName, onClick = { viewModel.toggleReply(kind) }, selected = state.myRepliesToPartner.any { it.kind == kind })
                            }
                        }
                    }
                    state.myMood?.value?.let { mood ->
                        Row(
                            Modifier.padding(top = if (state.partnerMood != null) 30.dp else 0.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PersonMark(people.markChar(mood.authorId), people.person(mood.authorId), size = 22.dp, modifier = Modifier.padding(bottom = 6.dp))
                            Text(feelingWord(mood.label, mood.intensity), style = type.feeling.copy(fontSize = 24.tsp, lineHeight = 31.tsp, color = colors.ink))
                            Text(mood.intensity.toString(), style = type.numeral.copy(fontSize = 20.tsp, color = colors.muted), modifier = Modifier.padding(bottom = 4.dp))
                        }
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

            // ── 一年前（本阶段只有心情）──
            if (state.yearAgoMoods.isNotEmpty()) {
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
                }
            }
        }
    }
}
