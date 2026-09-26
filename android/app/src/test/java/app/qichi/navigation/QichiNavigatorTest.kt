package app.qichi.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import app.qichi.shared.api.SummarySource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 返回一律回到进来的地方（人类 2026-09-26）：用真的 NavController 和 App 一样形状的导航图，
 * 打开页面、切标签、走深链，再像用户一样一路按返回，看依次回到哪些页面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class QichiNavigatorTest {
    private val roomId = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000001")
    private lateinit var nav: NavHostController
    private lateinit var navigator: QichiNavigator

    /** App 在前台（页面的状态要到这一步才会在切标签时存下来） */
    private class Foreground : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        nav = NavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setLifecycleOwner(Foreground())
            setViewModelStore(ViewModelStore())
        }
        nav.graph = nav.createGraph(startDestination = TodayGraph) {
            qichiGraph(
                todayHome = {}, chatHome = {}, togetherHome = {}, meHome = {},
                togetherPage = {}, mePage = {}, calendarDay = {}, eventList = {},
            )
        }
        navigator = QichiNavigator(nav, TabHistory())
    }

    /** 当前页面，换成路由对象方便比较 */
    private fun current(): Any {
        val entry = checkNotNull(nav.currentBackStackEntry)
        val destination = entry.destination
        return when {
            destination.hasRoute(TodayHome::class) -> TodayHome
            destination.hasRoute(ChatHome::class) -> entry.toRoute<ChatHome>()
            destination.hasRoute(TogetherHome::class) -> TogetherHome
            destination.hasRoute(MeHome::class) -> MeHome
            destination.hasRoute(TogetherPage::class) -> entry.toRoute<TogetherPage>()
            destination.hasRoute(MePage::class) -> entry.toRoute<MePage>()
            destination.hasRoute(CalendarDay::class) -> entry.toRoute<CalendarDay>()
            destination.hasRoute(EventList::class) -> entry.toRoute<EventList>()
            else -> error("没想到的页面：${destination.route}")
        }
    }

    /**
     * 像用户一样一路按返回直到退出 App，依次记下看到的页面（第一个是现在这一页）。
     * 和 App 里一样：标签根页面上按返回回到上一个用过的标签、没有就退出（QichiApp 的 BackHandler），其它页面退一页。
     */
    private fun backUntilExit(): List<Any> {
        val seen = mutableListOf(current())
        repeat(20) {
            if (navigator.isTabRoot(nav.currentDestination)) {
                if (!navigator.backToPreviousTab()) return seen
            } else {
                navigator.back()
            }
            seen += current()
        }
        error("按了 20 次返回还没退出：$seen")
    }

    @Test
    fun `从今天打开的页面：不切标签，返回回到今天`() {
        navigator.open(Page.Decisions, "d1")
        assertEquals(TopTab.Today, navigator.currentTab)
        assertEquals(listOf(TogetherPage(Page.Decisions, "d1"), TodayHome), backUntilExit())
    }

    @Test
    fun `从时间线点开一个决定：返回回到时间线，不经过决定首页`() {
        navigator.selectTab(TopTab.Together)
        navigator.open(Page.Timeline)
        navigator.open(Page.Decisions, "d1")
        assertEquals(
            listOf(TogetherPage(Page.Decisions, "d1"), TogetherPage(Page.Timeline), TogetherHome, TodayHome),
            backUntilExit(),
        )
    }

    @Test
    fun `一起首页的最近里点开的：返回直接回到一起`() {
        navigator.selectTab(TopTab.Together)
        navigator.open(Page.Plan, "p1")
        assertEquals(listOf(TogetherPage(Page.Plan, "p1"), TogetherHome, TodayHome), backUntilExit())
    }

    @Test
    fun `从聊天存进档案：留在聊天标签，返回回到聊天`() {
        navigator.selectTab(TopTab.Chat)
        navigator.open(Page.Archive, "new:m1")
        assertEquals(TopTab.Chat, navigator.currentTab)
        assertEquals(listOf(TogetherPage(Page.Archive, "new:m1"), ChatHome(), TodayHome), backUntilExit())
    }

    @Test
    fun `我写下的内容里点开一条：返回回到我写下的内容`() {
        navigator.selectTab(TopTab.Me)
        navigator.open(Page.MyContent)
        navigator.open(Page.Decisions, "d1")
        assertEquals(
            listOf(TogetherPage(Page.Decisions, "d1"), MePage(Page.MyContent), MeHome, TodayHome),
            backUntilExit(),
        )
    }

    @Test
    fun `连点两下只压一层`() {
        navigator.open(Page.Calendar)
        navigator.open(Page.Calendar)
        navigator.push(CalendarDay("2026-09-26"))
        navigator.push(CalendarDay("2026-09-26"))
        assertEquals(listOf(CalendarDay("2026-09-26"), TogetherPage(Page.Calendar), TodayHome), backUntilExit())
    }

    @Test
    fun `从某一页去聊天：在聊天首页按返回，回到原来那一页`() {
        navigator.selectTab(TopTab.Me)
        navigator.open(Page.MyContent)
        navigator.selectTab(TopTab.Chat) // 「我写下的内容」里的「去聊天」
        assertEquals(listOf(ChatHome(), MePage(Page.MyContent), MeHome, TodayHome), backUntilExit())
    }

    @Test
    fun `AI 引用的消息：跳到聊天里那条，按返回回到总结`() {
        val message = UUID.fromString("0192f000-aaaa-7bbb-8ccc-0000000000aa")
        navigator.selectTab(TopTab.Together)
        navigator.open(Page.Summary)
        navigator.openSource(roomId, SummarySource(1, "message", message, "晚饭吃什么", Instant.EPOCH))
        assertEquals(TopTab.Chat, navigator.currentTab)
        assertEquals(
            listOf(ChatHome(message.toString()), TogetherPage(Page.Summary), TogetherHome, TodayHome),
            backUntilExit(),
        )
    }

    @Test
    fun `AI 引用的日程：那一天压在总结上`() {
        val at = Instant.parse("2026-09-26T04:00:00Z")
        navigator.selectTab(TopTab.Together)
        navigator.open(Page.Summary)
        navigator.openSource(roomId, SummarySource(2, "event", UUID.randomUUID(), "看电影", at))
        val day = CalendarDay(at.atZone(ZoneId.systemDefault()).toLocalDate().toString())
        assertEquals(listOf(day, TogetherPage(Page.Summary), TogetherHome, TodayHome), backUntilExit())
    }

    @Test
    fun `切标签：每个标签连同压在上面的页面原样保留，今天也是`() {
        navigator.open(Page.Decisions, "d1") // 今天上打开一个决定
        navigator.handle(DeepLink.ToTab(roomId.toString(), TopTab.Chat)) // 点通知去聊天
        navigator.selectTab(TopTab.Me)
        navigator.open(Page.MyContent)
        navigator.selectTab(TopTab.Chat) // 「我写下的内容」里的「去聊天」
        navigator.selectTab(TopTab.Today)
        assertEquals(TogetherPage(Page.Decisions, "d1"), current())
        navigator.back()
        navigator.selectTab(TopTab.Me)
        assertEquals(MePage(Page.MyContent), current())
    }

    @Test
    fun `深链打开页面：压在当前页面上，返回回到点开之前的地方`() {
        navigator.selectTab(TopTab.Chat)
        navigator.handle(DeepLink.ToPage(roomId.toString(), Page.Mood, "m9"))
        assertEquals(TopTab.Chat, navigator.currentTab)
        assertEquals(listOf(TogetherPage(Page.Mood, "m9"), ChatHome(), TodayHome), backUntilExit())
    }

    @Test
    fun `深链去聊天的某条消息：聊天标签只留根页面并跳到那条`() {
        navigator.selectTab(TopTab.Chat)
        navigator.open(Page.Archive, "new:m1")
        navigator.selectTab(TopTab.Today)
        navigator.handle(DeepLink.ToTab(roomId.toString(), TopTab.Chat, "m2"))
        assertEquals(listOf(ChatHome("m2"), TodayHome), backUntilExit())
    }

    @Test
    fun `再点当前标签：回到它的根页面`() {
        navigator.selectTab(TopTab.Together)
        navigator.open(Page.Timeline)
        navigator.open(Page.Decisions, "d1")
        navigator.selectTab(TopTab.Together)
        assertEquals(listOf(TogetherHome, TodayHome), backUntilExit())
    }
}
