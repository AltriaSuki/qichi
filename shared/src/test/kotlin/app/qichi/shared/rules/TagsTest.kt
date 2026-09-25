package app.qichi.shared.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagsTest {
    @Test
    fun `中文、英文、分层`() {
        assertEquals(listOf("旅行/北方"), Tags.parse("冬天去看雪 #旅行/北方"))
        assertEquals(listOf("trip", "家/阳台"), Tags.parse("#trip 周末 #家/阳台"))
        assertEquals(listOf("吃"), Tags.parse("学做红烧肉#吃"))
    }

    @Test
    fun `标点结尾：标签到标点为止`() {
        assertEquals(listOf("旅行"), Tags.parse("想去海边 #旅行。"))
        assertEquals(listOf("家", "吃"), Tags.parse("#家，#吃！"))
        assertEquals(listOf("送给对方"), Tags.parse("（#送给对方）"))
        assertEquals(listOf("trip"), Tags.parse("see #trip, ok"))
    }

    @Test
    fun `井号后面是空格或标点的不算；前面紧跟字母数字的不算`() {
        assertEquals(emptyList(), Tags.parse("# 周末"))
        assertEquals(emptyList(), Tags.parse("#！"))
        assertEquals(emptyList(), Tags.parse("学 C# 和 abc#def"))
        assertEquals(emptyList(), Tags.parse("#/旅行"))
    }

    @Test
    fun `最多三层；空层跳过；结尾斜杠去掉；不重复`() {
        assertEquals(listOf("a/b/c"), Tags.parse("#a/b/c/d"))
        assertEquals(listOf("旅行/北方"), Tags.parse("#旅行//北方"))
        assertEquals(listOf("旅行"), Tags.parse("#旅行/ 出发"))
        assertEquals(listOf("家"), Tags.parse("#家 #家"))
    }

    @Test
    fun `上层也算：旅行下能找到旅行北方`() {
        assertEquals(listOf("旅行", "旅行/北方", "旅行/北方/雪山"), Tags.withAncestors("旅行/北方/雪山"))
        assertTrue(Tags.has("冬天去看雪 #旅行/北方", "旅行"))
        assertTrue(Tags.has("冬天去看雪 #旅行/北方", "旅行/北方"))
        assertFalse(Tags.has("#旅行者", "旅行"))
    }

    @Test
    fun `改名：子标签跟着改，别的不动；合并就是改成已有的名字`() {
        assertEquals("冬天去看雪 #出门/北方", Tags.rename("冬天去看雪 #旅行/北方", "旅行", "出门"))
        assertEquals("#出门。#旅行者 #家", Tags.rename("#旅行。#旅行者 #家", "旅行", "出门"))
        assertEquals("#出门/北方 和 #出门", Tags.rename("#旅行/北方 和 #出门", "旅行", "出门"))
        assertEquals("没有标签", Tags.rename("没有标签", "旅行", "出门"))
        // 只换属于标签的那一段，多出的第四层留在原文里
        assertEquals("#x/b/c/d", Tags.rename("#a/b/c/d", "a", "x"))
    }

    @Test
    fun `合法的标签名`() {
        assertTrue(Tags.isValid("出门/北方"))
        assertFalse(Tags.isValid(""))
        assertFalse(Tags.isValid("a/b/c/d"))
        assertFalse(Tags.isValid("有 空格"))
        assertFalse(Tags.isValid("#出门"))
    }

    @Test
    fun `显示时去掉标签`() {
        assertEquals("冬天去看雪", Tags.strip("冬天去看雪 #旅行/北方"))
        assertEquals("周末 去菜市场", Tags.strip("#家 周末 #吃 去菜市场"))
        assertEquals("学 C# 编程", Tags.strip("学 C# 编程"))
        assertEquals("", Tags.strip("#a/b/c/d"))
    }
}
