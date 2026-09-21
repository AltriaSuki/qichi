package app.qichi.navigation

import app.qichi.core.designsystem.component.romanNumeral
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
        assertTrue(Page.entries.filter { it.tab == TopTab.Together }.all { it.group != null })
    }

    @Test
    fun `一起的三组包含文档规定的页面`() {
        assertEquals(listOf("心情", "问答", "计划", "待办", "日历", "灵感"), Page.inGroup(TogetherGroup.Life).map { it.title })
        assertEquals(listOf("留言", "共同写作"), Page.inGroup(TogetherGroup.Create).map { it.title })
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
    fun `罗马数字`() {
        assertEquals(
            listOf("i", "ii", "iii", "iv", "v", "vi", "ix", "xii", "xl", "mmxxvi"),
            listOf(1, 2, 3, 4, 5, 6, 9, 12, 40, 2026).map(::romanNumeral),
        )
    }
}
