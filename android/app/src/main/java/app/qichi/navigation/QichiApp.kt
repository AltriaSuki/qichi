package app.qichi.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.QichiTabBar
import app.qichi.core.designsystem.component.TabItem
import app.qichi.feature.archive.ArchiveDetailScreen
import app.qichi.feature.archive.ArchiveListScreen
import app.qichi.feature.board.BoardListScreen
import app.qichi.feature.board.TopicScreen
import app.qichi.feature.calendar.CalendarDayScreen
import app.qichi.feature.calendar.CalendarMonthScreen
import app.qichi.feature.calendar.EventListScreen
import app.qichi.feature.chat.ChatScreen
import app.qichi.feature.chat.UnreadViewModel
import app.qichi.feature.decisions.DecisionDetailScreen
import app.qichi.feature.decisions.DecisionListScreen
import app.qichi.feature.ideas.IdeasScreen
import app.qichi.feature.me.AiPrefsScreen
import app.qichi.feature.me.AiUsageScreen
import app.qichi.feature.me.DisplayScreen
import app.qichi.feature.me.MeScreen
import app.qichi.feature.me.MyContentScreen
import app.qichi.feature.me.NotificationsScreen
import app.qichi.feature.me.ProfileScreen
import app.qichi.feature.me.RoomSettingsScreen
import app.qichi.feature.me.SecurityScreen
import app.qichi.feature.me.ShowcaseScreen
import app.qichi.feature.me.TrashScreen
import app.qichi.feature.mood.MoodScreen
import app.qichi.feature.plan.PlanDetailScreen
import app.qichi.feature.plan.PlanListScreen
import app.qichi.feature.qna.QnaScreen
import app.qichi.feature.reading.ReaderScreen
import app.qichi.feature.reading.ShelfScreen
import app.qichi.feature.review.ReviewListScreen
import app.qichi.feature.review.ReviewScreen
import app.qichi.feature.room.MembersScreen
import app.qichi.feature.summary.SummaryScreen
import app.qichi.feature.timeline.TimelineScreen
import app.qichi.feature.today.TodayScreen
import app.qichi.feature.todo.TodoScreen
import app.qichi.feature.together.TogetherHubScreen
import app.qichi.feature.together.TogetherHubViewModel
import app.qichi.feature.writing.DocumentEditorScreen
import app.qichi.feature.writing.DocumentListScreen

/** 页面进出：200ms 淡入 + 8dp 位移；「减少动画」时直接切换。 */
private const val PAGE_TRANSITION_MILLIS = 200

/**
 * App 的外壳：NavHost（四个标签各一个嵌套图）+ 标签根页面才显示的底部标签栏。
 * @param pendingLink 待处理的深链（来自通知或外部链接），处理后调用 [onLinkHandled]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QichiApp(
    pendingLink: DeepLink?,
    onLinkHandled: () -> Unit,
    navigator: QichiNavigator = rememberQichiNavigator(),
) {
    val destination = navigator.currentDestination()
    val currentTab = navigator.currentTab(destination)
    val atTabRoot = navigator.isTabRoot(destination)
    val roomId = LocalRoomId.current
    val unread by hiltViewModel<UnreadViewModel, UnreadViewModel.Factory>(key = "unread-$roomId") { it.create(roomId) }
        .count.collectAsStateWithLifecycle()

    LaunchedEffect(pendingLink, destination != null) {
        if (pendingLink != null && destination != null) {
            navigator.handle(pendingLink)
            onLinkHandled()
        }
    }

    val reduceMotion = QichiTheme.reduceMotion
    val shift = with(LocalDensity.current) { 8.dp.roundToPx() }
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) EnterTransition.None
        else fadeIn(tween(PAGE_TRANSITION_MILLIS)) + slideInHorizontally(tween(PAGE_TRANSITION_MILLIS)) { shift }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) ExitTransition.None else fadeOut(tween(PAGE_TRANSITION_MILLIS))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) EnterTransition.None else fadeIn(tween(PAGE_TRANSITION_MILLIS))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) ExitTransition.None
        else fadeOut(tween(PAGE_TRANSITION_MILLIS)) + slideOutHorizontally(tween(PAGE_TRANSITION_MILLIS)) { shift }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(QichiTheme.colors.background),
    ) {
        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navigator.navController,
                startDestination = TodayGraph,
                enterTransition = enter,
                exitTransition = exit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                navigation<TodayGraph>(startDestination = TodayHome) {
                    composable<TodayHome> { val todayRoom = LocalRoomId.current; TodayScreen(roomId = todayRoom, onOpen = { navigator.open(it) }, onOpenPlan = { navigator.open(Page.Plan, it.toString()) },
                        onOpenDecision = { navigator.open(Page.Decisions, it.toString()) },
                        onOpenMessage = { navigator.handle(DeepLink.ToTab(todayRoom.toString(), TopTab.Chat, it.toString())) }) }
                }
                navigation<ChatGraph>(startDestination = ChatHome()) {
                    composable<ChatHome> { entry ->
                        val jumpTo = entry.toRoute<ChatHome>().jumpTo?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                        val chatRoom = LocalRoomId.current
                        ChatScreen(
                            roomId = chatRoom,
                            jumpTo = jumpTo,
                            onArchive = { navigator.open(Page.Archive, "new:${it.id}") },
                            onOpenSource = { src -> navigator.openSource(chatRoom, src) },
                        )
                    }
                }
                navigation<TogetherGraph>(startDestination = TogetherHome) {
                    composable<TogetherHome> {
                        var group by rememberSaveable { mutableStateOf(TogetherGroup.Life) }
                        val hubRoom = LocalRoomId.current
                        val hub = hiltViewModel<TogetherHubViewModel, TogetherHubViewModel.Factory>(key = "hub-$hubRoom") { it.create(hubRoom) }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        LaunchedEffect(hub) {
                            hub.saved.collect { android.widget.Toast.makeText(context, "记下了，在「灵感」里", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                        val counts by hub.counts.collectAsStateWithLifecycle()
                        val hubPeople by hub.people.collectAsStateWithLifecycle()
                        val recent by hub.recent.collectAsStateWithLifecycle()
                        TogetherHubScreen(
                            group = group, onGroupChange = { group = it }, onOpen = { navigator.open(it) }, counts = counts,
                            people = hubPeople, recent = recent, onOpenItem = { page, id -> navigator.open(page, id) }, onAddIdea = hub::addIdea,
                        )
                    }
                    composable<TogetherPage> { entry ->
                        val route = entry.toRoute<TogetherPage>()
                        val roomId = LocalRoomId.current
                        when (route.page) {
                            Page.Mood -> MoodScreen(roomId = roomId, onBack = navigator::back)
                            Page.Qna -> QnaScreen(roomId = roomId, onBack = navigator::back)
                            Page.Todo -> TodoScreen(roomId = roomId, onBack = navigator::back)
                            Page.Calendar -> CalendarMonthScreen(
                                roomId = roomId,
                                onBack = navigator::back,
                                onDayClick = { date -> navigator.navController.navigate(CalendarDay(date.toString())) },
                                onEventsClick = { navigator.navController.navigate(EventList()) },
                                onNewEvent = { navigator.navController.navigate(EventList(create = true)) },
                            )
                            Page.Plan -> {
                                val planId = route.id?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (planId == null) {
                                    PlanListScreen(roomId = roomId, onBack = navigator::back, onOpen = { navigator.open(Page.Plan, it.toString()) })
                                } else {
                                    PlanDetailScreen(roomId = roomId, planId = planId, onBack = navigator::back)
                                }
                            }
                            Page.Ideas -> IdeasScreen(roomId = roomId, onBack = navigator::back)
                            Page.Summary -> SummaryScreen(roomId = roomId, onBack = navigator::back, onOpenSource = { src -> navigator.openSource(roomId, src) })
                            Page.Reading -> {
                                val bookId = route.id?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (bookId == null) {
                                    ShelfScreen(roomId = roomId, onBack = navigator::back, onOpen = { navigator.open(Page.Reading, it.toString()) })
                                } else {
                                    ReaderScreen(roomId = roomId, bookId = bookId, onBack = navigator::back)
                                }
                            }
                            Page.Timeline -> TimelineScreen(roomId = roomId, onBack = navigator::back, onOpen = { e ->
                                when (e.kind) {
                                    app.qichi.shared.model.TimelineEntryKind.Decision -> navigator.open(Page.Decisions, e.refId.toString())
                                    app.qichi.shared.model.TimelineEntryKind.Plan -> navigator.open(Page.Plan, e.refId.toString())
                                    app.qichi.shared.model.TimelineEntryKind.Idea -> navigator.open(Page.Ideas)
                                    app.qichi.shared.model.TimelineEntryKind.Photo -> Unit
                                }
                            })
                            Page.Decisions -> {
                                val decisionId = route.id?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (decisionId == null) {
                                    DecisionListScreen(roomId = roomId, onBack = navigator::back, onOpen = { navigator.open(Page.Decisions, it.toString()) })
                                } else {
                                    DecisionDetailScreen(roomId = roomId, decisionId = decisionId, onBack = navigator::back)
                                }
                            }
                            Page.Archive -> {
                                // id：条目；或「new:消息 id」（从聊天「存进档案」进来）
                                val raw = route.id
                                val fromMessage = raw?.removePrefix("new:")?.takeIf { raw.startsWith("new:") }
                                    ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                val itemId = raw?.takeIf { !it.startsWith("new:") }?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (itemId == null) {
                                    ArchiveListScreen(roomId = roomId, fromMessageId = fromMessage, onBack = navigator::back,
                                        onOpen = { navigator.open(Page.Archive, it.toString()) })
                                } else {
                                    ArchiveDetailScreen(roomId = roomId, itemId = itemId, onBack = navigator::back,
                                        onOpenMessage = { navigator.handle(DeepLink.ToTab(roomId.toString(), TopTab.Chat, it.toString())) })
                                }
                            }
                            Page.Board -> {
                                // id 是「主题」或「主题:留言」（从搜索点进来时滚到那一条）
                                val parts = route.id?.split(":").orEmpty()
                                val topicId = parts.getOrNull(0)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                val postId = parts.getOrNull(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (topicId == null) {
                                    BoardListScreen(roomId = roomId, onBack = navigator::back, onOpen = { t, p ->
                                        navigator.open(Page.Board, if (p == null) t.toString() else "$t:$p")
                                    })
                                } else {
                                    TopicScreen(roomId = roomId, topicId = topicId, focusPostId = postId, onBack = navigator::back)
                                }
                            }
                            Page.Review -> {
                                val reviewId = route.id?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (reviewId == null) {
                                    ReviewListScreen(roomId = roomId, onBack = navigator::back, onOpen = { navigator.open(Page.Review, it.toString()) })
                                } else {
                                    ReviewScreen(roomId = roomId, documentId = reviewId, onBack = navigator::back)
                                }
                            }
                            Page.Writing -> {
                                val docId = route.id?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                                if (docId == null) {
                                    DocumentListScreen(roomId = roomId, onBack = navigator::back, onOpen = { navigator.open(Page.Writing, it.toString()) })
                                } else {
                                    DocumentEditorScreen(roomId = roomId, documentId = docId, onBack = navigator::back)
                                }
                            }
                            else -> PagePlaceholder(route.page.title, onBack = navigator::back)
                        }
                    }

                    composable<CalendarDay> { entry ->
                        val route = entry.toRoute<CalendarDay>()
                        val roomId = LocalRoomId.current
                        val date = java.time.LocalDate.parse(route.date)
                        CalendarDayScreen(roomId = roomId, date = date, onBack = navigator::back)
                    }
                    composable<EventList> { entry ->
                        EventListScreen(roomId = LocalRoomId.current, onBack = navigator::back, startCreating = entry.toRoute<EventList>().create)
                    }
                }
                navigation<MeGraph>(startDestination = MeHome) {
                    composable<MeHome> { MeScreen(roomId = LocalRoomId.current, onOpen = { navigator.open(it) }) }
                    composable<MePage> { entry ->
                        val route = entry.toRoute<MePage>()
                        when (route.page) {
                            Page.Members -> MembersScreen(roomId = LocalRoomId.current, onBack = navigator::back)
                            Page.Profile -> ProfileScreen(onBack = navigator::back)
                            Page.Display -> DisplayScreen(onBack = navigator::back)
                            Page.Trash -> TrashScreen(roomId = LocalRoomId.current, onBack = navigator::back)
                            Page.AiUsage -> AiUsageScreen(onBack = navigator::back)
                            Page.MyContent -> {
                                val meRoom = LocalRoomId.current
                                MyContentScreen(roomId = meRoom, onBack = navigator::back, onOpen = { navigator.open(it) },
                                    onOpenChat = { navigator.selectTab(TopTab.Chat) })
                            }
                            Page.Security -> SecurityScreen(onBack = navigator::back)
                            Page.Showcase -> ShowcaseScreen(onBack = navigator::back)
                            Page.Notifications -> NotificationsScreen(onBack = navigator::back)
                            Page.AiPrefs -> AiPrefsScreen(onBack = navigator::back)
                            Page.RoomSettings -> RoomSettingsScreen(roomId = LocalRoomId.current, onBack = navigator::back)
                            else -> PagePlaceholder(route.page.title, onBack = navigator::back)
                        }
                    }
                }
            }
        }
        // 标签根页面按返回：回到上一个用过的标签；没有历史时退出。
        // 必须写在 NavHost 之后：后注册的返回回调优先，才能盖过 NavHost 自己的返回处理。
        BackHandler(enabled = atTabRoot) {
            if (!navigator.backToPreviousTab()) {
                navigator.navController.context.findActivity()?.finish()
            }
        }
        // 输入法弹出时收起标签栏，让聊天输入框直接贴着键盘
        if (atTabRoot && !WindowInsets.isImeVisible) {
            QichiTabBar(
                items = TopTab.entries.map { tab ->
                    TabItem(tab.label, tab.icon, selected = tab == currentTab, badge = if (tab == TopTab.Chat) unread else null)
                },
                onSelect = { navigator.selectTab(TopTab.entries[it]) },
            )
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
