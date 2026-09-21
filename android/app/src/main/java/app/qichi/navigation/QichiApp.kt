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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.QichiTabBar
import app.qichi.core.designsystem.component.TabItem
import app.qichi.feature.calendar.EventListScreen
import app.qichi.feature.chat.ChatScreen
import app.qichi.feature.me.DisplayScreen
import app.qichi.feature.me.MeScreen
import app.qichi.feature.mood.MoodScreen
import app.qichi.feature.today.TodayScreen
import app.qichi.feature.todo.TodoScreen
import app.qichi.feature.me.ProfileScreen
import app.qichi.feature.me.RoomSettingsScreen
import app.qichi.feature.room.MembersScreen
import app.qichi.feature.together.TogetherHubScreen

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
                    composable<TodayHome> { TodayScreen(roomId = LocalRoomId.current, onOpen = { navigator.open(it) }) }
                }
                navigation<ChatGraph>(startDestination = ChatHome()) {
                    composable<ChatHome> { ChatScreen(roomId = LocalRoomId.current) }
                }
                navigation<TogetherGraph>(startDestination = TogetherHome) {
                    composable<TogetherHome> {
                        var group by rememberSaveable { mutableStateOf(TogetherGroup.Life) }
                        TogetherHubScreen(group = group, onGroupChange = { group = it }, onOpen = { navigator.open(it) })
                    }
                    composable<TogetherPage> { entry ->
                        val route = entry.toRoute<TogetherPage>()
                        val roomId = LocalRoomId.current
                        when (route.page) {
                            Page.Mood -> MoodScreen(roomId = roomId, onBack = navigator::back)
                            Page.Todo -> TodoScreen(roomId = roomId, onBack = navigator::back)
                            Page.Calendar -> EventListScreen(roomId = roomId, onBack = navigator::back)
                            else -> PagePlaceholder(route.page.title, onBack = navigator::back)
                        }
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
                items = TopTab.entries.map { TabItem(it.label, selected = it == currentTab) },
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
