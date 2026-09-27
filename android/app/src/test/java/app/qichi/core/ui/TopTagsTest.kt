package app.qichi.core.ui

import org.junit.Test
import kotlin.test.assertEquals

class TopTagsTest {
    @Test
    fun `顶部筛选按顶层标签用得多少排`() {
        assertEquals(listOf("旅行", "吃", "家"), topTags(listOf("#旅行/北方", "#旅行 #吃", "#家", "#吃", "#旅行/海边")))
    }
}
