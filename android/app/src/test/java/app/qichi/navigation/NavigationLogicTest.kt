package app.qichi.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationLogicTest {

    @Test
    fun `解析标签深链`() {
        assertEquals(DeepLink.ToTab("test", TopTab.Chat), DeepLink.parse("qichi://room/test/chat"))
        assertEquals(DeepLink.ToTab("r1", TopTab.Chat, "m9"), DeepLink.parse("qichi://room/r1/chat/m9"))
        assertEquals(DeepLink.ToTab("r1", TopTab.Today), DeepLink.parse("qichi://room/r1/today/"))
    }

    @Test
    fun `解析二级页面深链`() {
        assertEquals(DeepLink.ToPage("r1", Page.Mood, "m9"), DeepLink.parse("qichi://room/r1/mood/m9"))
        assertEquals(DeepLink.ToPage("r1", Page.RoomSettings), DeepLink.parse("qichi://room/r1/room-settings"))
        assertEquals(
            DeepLink.ToPage("0192f000-aaaa-7bbb-8ccc-000000000001", Page.Plan),
            DeepLink.parse("qichi://room/0192f000-aaaa-7bbb-8ccc-000000000001/plan?from=push"),
        )
    }

    @Test
    fun `格式不对的深链返回 null`() {
        listOf(
            null, "", "https://qichi.app/room/r1/chat", "qichi://room/r1", "qichi://room/r1/unknown",
            "qichi://room/r1/chat/a/b", "qichi://other/r1/chat", "qichi://room/r%2F1/chat", "qichi://room/r1/mood/..",
        ).forEach { assertNull(DeepLink.parse(it), "应解析失败：$it") }
    }

    @Test
    fun `生成的深链可以解析回来`() {
        Page.entries.forEach { page ->
            assertEquals(DeepLink.ToPage("r1", page, "x"), DeepLink.parse(DeepLink.of("r1", page, "x")))
        }
        assertEquals("qichi://room/r1/chat", DeepLink.of("r1", TopTab.Chat))
    }

    @Test
    fun `页面 slug 不重复，与标签 slug 也不冲突`() {
        val slugs = Page.entries.map { it.slug } + TopTab.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(Page.entries.filter { it.tab == TopTab.Together && it != Page.Tags }.all { it.group != null })
    }

    @Test
    fun `一起的三组包含文档规定的页面`() {
        assertEquals(listOf("心情", "问答", "计划", "待办", "日历", "灵感"), Page.inGroup(TogetherGroup.Life).map { it.title })
        assertEquals(listOf("写作", "留言"), Page.inGroup(TogetherGroup.Create).map { it.title })
        assertEquals(listOf("档案", "决定", "时间线", "阅读", "审稿", "总结"), Page.inGroup(TogetherGroup.Look).map { it.title })
    }

    @Test
    fun `标签历史：返回时回到上一个用过的标签`() {
        val h = TabHistory()
        h.onSwitch(TopTab.Today, TopTab.Chat)
        h.onSwitch(TopTab.Chat, TopTab.Together)
        assertEquals(TopTab.Chat, h.popPrevious(TopTab.Together))
        assertEquals(TopTab.Today, h.popPrevious(TopTab.Chat))
        assertNull(h.popPrevious(TopTab.Today))
    }

    @Test
    fun `标签历史里同一个标签只出现一次`() {
        val h = TabHistory()
        h.onSwitch(TopTab.Today, TopTab.Chat)
        h.onSwitch(TopTab.Chat, TopTab.Today)
        h.onSwitch(TopTab.Today, TopTab.Chat)
        assertEquals(listOf(TopTab.Today), h.entries)
        h.onSwitch(TopTab.Chat, TopTab.Chat)
        assertEquals(listOf(TopTab.Today), h.entries)
    }

    @Test
    fun `功能首页总是直接压在一起上`() {
        // 从时间线点进灵感：时间线退掉
        assertEquals(OpenPlan(null, listOf(TogetherPage(Page.Ideas))), plan(listOf(TogetherPage(Page.Timeline)), Page.Ideas, null))
        // 从聊天存进档案（new:）也算功能首页
        assertEquals(OpenPlan(null, listOf(TogetherPage(Page.Archive, "new:m1"))), plan(emptyList(), Page.Archive, "new:m1"))
        // 已经打开过的功能首页：退回去，不重建
        val plans = TogetherPage(Page.Plan)
        assertEquals(OpenPlan(plans, emptyList()), plan(listOf(plans, TogetherPage(Page.Plan, "p1")), Page.Plan, null))
    }

    @Test
    fun `单项页下面总是它的功能首页`() {
        val decisions = TogetherPage(Page.Decisions)
        // 从今天直接打开一个决定：先压决定首页
        assertEquals(OpenPlan(null, listOf(decisions, TogetherPage(Page.Decisions, "d1"))), plan(emptyList(), Page.Decisions, "d1"))
        // 从时间线打开：时间线退掉
        assertEquals(
            OpenPlan(null, listOf(decisions, TogetherPage(Page.Decisions, "d1"))),
            plan(listOf(TogetherPage(Page.Timeline)), Page.Decisions, "d1"),
        )
        // 从决定首页打开：直接压上
        assertEquals(OpenPlan(decisions, listOf(TogetherPage(Page.Decisions, "d1"))), plan(listOf(decisions), Page.Decisions, "d1"))
        // 从一个决定打开另一个：换掉，返回仍回首页
        assertEquals(
            OpenPlan(decisions, listOf(TogetherPage(Page.Decisions, "d2"))),
            plan(listOf(decisions, TogetherPage(Page.Decisions, "d1")), Page.Decisions, "d2"),
        )
        // 已经在这一项上：不动
        val d1 = TogetherPage(Page.Decisions, "d1")
        assertEquals(OpenPlan(d1, emptyList()), plan(listOf(decisions, d1), Page.Decisions, "d1"))
    }

    @Test
    fun `一起下的页面都有功能颜色和图标`() {
        Page.entries.filter { it.tab == TopTab.Together && it != Page.Tags }.forEach { assertEquals(it.title, it.feature.title) }
    }

    private fun plan(stack: List<TogetherPage>, page: Page, id: String?) =
        planOpen(stack.lastOrNull(), TogetherPage(page) in stack, page, id)
}
