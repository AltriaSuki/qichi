package app.qichi.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute

/**
 * 导航的唯一入口：切标签、打开二级页面、处理深链、返回。
 * 四个标签各自是一个嵌套导航图，用 saveState / restoreState 保存各自的返回栈。
 */
@Stable
class QichiNavigator(
    val navController: NavHostController,
    private val tabHistory: TabHistory,
) {
    fun currentTab(destination: NavDestination?): TopTab {
        val hierarchy = destination?.hierarchy ?: return TopTab.Today
        return when {
            hierarchy.any { it.hasRoute(ChatGraph::class) } -> TopTab.Chat
            hierarchy.any { it.hasRoute(TogetherGraph::class) } -> TopTab.Together
            hierarchy.any { it.hasRoute(MeGraph::class) } -> TopTab.Me
            else -> TopTab.Today
        }
    }

    /** 当前页面是不是某个标签的根页面（只在根页面显示底部标签栏）。 */
    fun isTabRoot(destination: NavDestination?): Boolean = destination != null && (
        destination.hasRoute(TodayHome::class) || destination.hasRoute(ChatHome::class) ||
            destination.hasRoute(TogetherHome::class) || destination.hasRoute(MeHome::class)
        )

    private val current: TopTab get() = currentTab(navController.currentDestination)

    /**
     * 点底部标签：切到别的标签时恢复它的返回栈；再点当前标签则回到它的根页面。
     */
    fun selectTab(tab: TopTab) {
        val from = current
        if (from == tab) {
            popToHome(tab)
            return
        }
        tabHistory.onSwitch(from, tab)
        switchTo(tab, restore = true)
    }

    /**
     * 在页面所属的标签里打开一个二级页面。「一起」下的页面按 [planOpen] 整理返回栈：
     * 功能首页总是直接压在「一起」上，单项页下面总是它的功能首页。
     */
    fun open(page: Page, id: String? = null) {
        if (current != page.tab) {
            tabHistory.onSwitch(current, page.tab)
            switchTo(page.tab, restore = true)
        }
        push(page, id)
    }

    private fun push(page: Page, id: String?) {
        // 标签页是灵感、档案下面的一页：直接压上，返回回到进来的地方
        if (page.tab != TopTab.Together || page == Page.Tags) {
            navController.navigate(page.route(id))
            return
        }
        val top = navController.currentBackStackEntry
            ?.takeIf { it.destination.hasRoute(TogetherPage::class) }?.toRoute<TogetherPage>()
        // getBackStackEntry 找不到时抛异常：用它判断功能首页在不在返回栈里
        val homeOnStack = runCatching { navController.getBackStackEntry(TogetherPage(page)) }.isSuccess
        val plan = planOpen(top, homeOnStack, page, id)
        if (plan.popTo == null) navController.popBackStack<TogetherHome>(inclusive = false)
        else navController.popBackStack(plan.popTo, inclusive = false)
        plan.push.forEach { navController.navigate(it) }
    }

    fun back() {
        navController.popBackStack()
    }

    /**
     * 在标签根页面按返回：有标签历史就回到上一个标签，返回 true；否则返回 false，交给系统（退出）。
     */
    fun backToPreviousTab(): Boolean {
        val previous = tabHistory.popPrevious(current) ?: return false
        switchTo(previous, restore = true)
        return true
    }

    /** 深链：切到对应标签，清掉那个标签原来的返回栈，再压入目标页面（返回时回到标签根页面）。 */
    fun handle(link: DeepLink) {
        when (link) {
            is DeepLink.ToTab -> {
                tabHistory.onSwitch(current, link.tab)
                switchTo(link.tab, restore = false)
                if (link.tab == TopTab.Chat && link.id != null) {
                    navController.navigate(ChatHome(jumpTo = link.id)) {
                        popUpTo(ChatGraph) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is DeepLink.ToPage -> {
                tabHistory.onSwitch(current, link.page.tab)
                switchTo(link.page.tab, restore = false)
                push(link.page, link.id)
            }
        }
    }

    /**
     * 打开 AI 引用的来源（总结、问 AI 回答里的 [n]）：跳到原来那条记录所在的页面。
     * 日程按手机本地时区换算成那一天。
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
            "event" -> {
                open(Page.Calendar)
                navController.navigate(CalendarDay(src.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()))
            }
        }
    }

    /**
     * 切到某个标签。离开的标签总是保存返回栈（saveState）。
     * 「今天」没有二级页面，切回时不恢复状态：Navigation 会把离开其它标签时保存的状态
     * 也登记在起始目的地（今天）名下，恢复它会错把别的标签的页面带回来。
     * @param restore 是否恢复目标标签之前的返回栈；深链用 false，只留根页面
     */
    private fun switchTo(tab: TopTab, restore: Boolean) {
        navController.navigate(tab.graph()) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = restore && tab != TopTab.Today
        }
        if (!restore) {
            // 清掉这个标签里之前压着的页面，只留根页面
            popToHome(tab)
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
