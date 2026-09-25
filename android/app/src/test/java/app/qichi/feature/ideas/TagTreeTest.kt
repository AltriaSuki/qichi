package app.qichi.feature.ideas

import org.junit.Test
import kotlin.test.assertEquals

class TagTreeTest {
    @Test
    fun `上层也算上子标签；一条里重复只算一次；子标签跟在父标签下`() {
        val tree = tagTree(
            ideaTexts = listOf("冬天去看雪 #旅行/北方", "海边书店 #旅行/海边", "#旅行 #旅行/海边 日出", "阳台躺椅 #家/阳台"),
            archiveTexts = listOf("靠窗 #旅行/海边", "吵架不过夜 #家"),
        )
        assertEquals(
            listOf(
                TagNode("旅行", 0, 3, 1),
                TagNode("旅行/海边", 1, 2, 1),
                TagNode("旅行/北方", 1, 1, 0, last = true),
                TagNode("家", 0, 1, 1, last = true),
                TagNode("家/阳台", 1, 1, 0, last = true),
            ),
            tree,
        )
        assertEquals("海边", tree[1].name)
    }

    @Test
    fun `顶部筛选按顶层标签用得多少排`() {
        assertEquals(listOf("旅行", "吃", "家"), topTags(listOf("#旅行/北方", "#旅行 #吃", "#家", "#吃", "#旅行/海边")))
    }
}
