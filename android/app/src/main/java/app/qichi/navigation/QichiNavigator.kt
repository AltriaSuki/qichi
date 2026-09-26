package app.qichi.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute

/**
 * 导航的唯一入口：切标签、打开页面、处理深链、返回。
 *
 * **返回一律回到进来的地方**（人类 2026-09-26）：页面从哪里打开，就压在哪里——不切标签，
 * 也不替用户补上没去过的上一级（以前从时间线点开一个决定，返回先到「决定」首页、再到「一起」）。
 *
 * 返回栈的样子（图见 [qichiGraph]）：栈底是当前标签的导航图和它的根页面，上面是依次打开的页面。
 * 切标签时把离开的标签整个（连同压在上面的页面）按它自己的导航图存起来，回来时原样恢复，「今天」也一样；
 * 所以跳到聊天里的某条消息、再在聊天首页按返回，回到的仍是原来那一页。
 */
@Stable
class QichiNavigator(
    val navController: NavHostController,
    private val tabHistory: TabHistory,
) {
    /** 当前在哪个标签：返回栈底下那个标签的导航图（切标签时整个换掉，任何时候只有一个）。 */
    val currentTab: TopTab
        get() = TopTab.entries.firstOrNull { tab ->
            // getBackStackEntry 找不到时抛异常
            runCatching { navController.getBackStackEntry(tab.graph()) }.isSuccess
        } ?: TopTab.Today

    /** 当前页面是不是某个标签的根页面（只在根页面显示底部标签栏）。 */
    fun isTabRoot(destination: NavDestination?): Boolean = destination != null && (
        destination.hasRoute(TodayHome::class) || destination.hasRoute(ChatHome::class) ||
            destination.hasRoute(TogetherHome::class) || destination.hasRoute(MeHome::class)
        )

    /** 点底部标签：切到别的标签时恢复它的返回栈；再点当前标签则回到它的根页面。 */
    fun selectTab(tab: TopTab) {
        val from = currentTab
        if (from == tab) {
            popToHome(tab)
            return
        }
        tabHistory.onSwitch(from, tab)
        switchTo(from, tab)
    }

    /** 打开一个页面，压在当前页面上：返回就回到这里。 */
    fun open(page: Page, id: String? = null) = push(page.route(id))

    /** 日历某一天、日程列表等不在 [Page] 里的页面也一样。已经在这一页上时不动（连点两下不压两层）。 */
    fun push(route: Any) {
        if (isOnTop(route)) return
        navController.navigate(route)
    }

    fun back() {
        navController.popBackStack()
    }

    /** 在标签根页面按返回：有标签历史就回到上一个标签（原样恢复），返回 true；否则返回 false，交给系统（退出）。 */
    fun backToPreviousTab(): Boolean {
        val from = currentTab
        val previous = tabHistory.popPrevious(from) ?: return false
        switchTo(from, previous)
        return true
    }

    /**
     * 深链（通知、链接）：
     * - 某个页面：和 App 里打开一样压在当前页面上，返回回到点开之前的地方
     * - 某个标签：切到那个标签的根页面（聊天带消息 id 时跳到那条消息）；在那里按返回回到之前的标签和页面。
     *   本来就在这个标签里时，压在它上面的页面会退掉（每个标签只有一个根页面）
     */
    fun handle(link: DeepLink) {
        when (link) {
            is DeepLink.ToTab -> {
                val from = currentTab
                if (from != link.tab) {
                    tabHistory.onSwitch(from, link.tab)
                    switchTo(from, link.tab)
                }
                popToHome(link.tab)
                if (link.tab == TopTab.Chat && link.id != null) {
                    navController.navigate(ChatHome(jumpTo = link.id)) {
                        popUpTo(ChatGraph) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is DeepLink.ToPage -> open(link.page, link.id)
        }
    }

    /**
     * 打开 AI 引用的来源（总结、问 AI 回答里的 [n]）：原来那条记录所在的页面，压在当前页面上。
     * 消息在聊天标签里（切过去，返回回来）；日程按手机本地时区换算成那一天。
     */
    fun openSource(roomId: java.util.UUID, src: app.qichi.shared.api.SummarySource) {
        when (src.type) {
            "message" -> handle(DeepLink.ToTab(roomId.toString(), TopTab.Chat, src.id.toString()))
            "decision" -> open(Page.Decisions, src.id.toString())
            "plan" -> open(Page.Plan, src.id.toString())
            "archive_item" -> open(Page.Archive, src.id.toString())
            "idea" -> open(Page.Ideas)
            "mood" -> open(Page.Mood)
            "todo" -> open(Page.Todo)
            "event" -> push(CalendarDay(src.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()))
            // AI 自己查到的（P11）
            "qna_round" -> open(Page.Qna)
            "document" -> open(Page.Writing, src.id.toString())
            "board_topic" -> open(Page.Board, src.id.toString())
            "book" -> open(Page.Reading, src.id.toString())
            "review_document" -> open(Page.Review, src.id.toString())
            "summary" -> open(Page.Summary)
        }
    }

    /**
     * 从 [from] 切到 [to]：离开的标签整个退掉，按它自己的导航图存起来（inclusive）；到了 [to] 恢复它存着的返回栈。
     *
     * 不能像以前那样退到「今天」的根页面为止（非 inclusive）：那样存下的状态会登记在「今天」名下，
     * 恢复「今天」时带回来的是别的标签的页面，所以以前「今天」从不恢复、压在上面的页面一切标签就丢了。
     */
    private fun switchTo(from: TopTab, to: TopTab) {
        navController.navigate(to.graph()) {
            popUpTo(from.graph()) {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun popToHome(tab: TopTab) {
        when (tab) {
            TopTab.Today -> navController.popBackStack<TodayHome>(inclusive = false)
            TopTab.Chat -> navController.popBackStack<ChatHome>(inclusive = false)
            TopTab.Together -> navController.popBackStack<TogetherHome>(inclusive = false)
            TopTab.Me -> navController.popBackStack<MeHome>(inclusive = false)
        }
    }

    private fun isOnTop(route: Any): Boolean {
        val top = navController.currentBackStackEntry ?: return false
        val destination = top.destination
        return when (route) {
            is TogetherPage -> destination.hasRoute(TogetherPage::class) && top.toRoute<TogetherPage>() == route
            is MePage -> destination.hasRoute(MePage::class) && top.toRoute<MePage>() == route
            is CalendarDay -> destination.hasRoute(CalendarDay::class) && top.toRoute<CalendarDay>() == route
            is EventList -> destination.hasRoute(EventList::class) && top.toRoute<EventList>() == route
            else -> false
        }
    }
}

@Composable
fun rememberQichiNavigator(): QichiNavigator {
    val navController = rememberNavController()
    val history = rememberSaveable(
        saver = listSaver(save = { h -> h.entries.map { it.name } }, restore = { TabHistory(it.map(TopTab::valueOf)) }),
    ) { TabHistory() }
    return remember(navController, history) { QichiNavigator(navController, history) }
}

@Composable
fun QichiNavigator.currentDestination(): NavDestination? {
    val entry by navController.currentBackStackEntryAsState()
    return entry?.destination
}
