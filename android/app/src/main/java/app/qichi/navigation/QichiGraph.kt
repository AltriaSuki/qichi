package app.qichi.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation

/**
 * 导航图的形状（返回一律回到进来的地方，人类 2026-09-26）：
 * - 四个标签各一个嵌套图，**只装它的根页面**；切标签时整个标签（连同压在上面的页面）按这个图存起来、原样恢复
 * - 其它页面都放在最外层，不属于任何标签：从哪里打开就压在哪个标签的返回栈上，返回回到打开它的那一页
 *
 * 页面若放进某个标签的图里，从别的标签打开时会把那个标签的图一起压上来，下次切标签就会存错、恢复错（见 [QichiNavigator]）。
 * QichiApp 提供各页面的内容；测试用空内容建一样形状的图。
 */
fun NavGraphBuilder.qichiGraph(
    todayHome: @Composable (NavBackStackEntry) -> Unit,
    chatHome: @Composable (NavBackStackEntry) -> Unit,
    togetherHome: @Composable (NavBackStackEntry) -> Unit,
    meHome: @Composable (NavBackStackEntry) -> Unit,
    togetherPage: @Composable (NavBackStackEntry) -> Unit,
    mePage: @Composable (NavBackStackEntry) -> Unit,
    calendarDay: @Composable (NavBackStackEntry) -> Unit,
    eventList: @Composable (NavBackStackEntry) -> Unit,
) {
    navigation<TodayGraph>(startDestination = TodayHome) { composable<TodayHome> { todayHome(it) } }
    navigation<ChatGraph>(startDestination = ChatHome()) { composable<ChatHome> { chatHome(it) } }
    navigation<TogetherGraph>(startDestination = TogetherHome) { composable<TogetherHome> { togetherHome(it) } }
    navigation<MeGraph>(startDestination = MeHome) { composable<MeHome> { meHome(it) } }
    composable<TogetherPage> { togetherPage(it) }
    composable<MePage> { mePage(it) }
    composable<CalendarDay> { calendarDay(it) }
    composable<EventList> { eventList(it) }
}
