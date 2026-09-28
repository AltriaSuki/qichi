package app.qichi.feature.together

import kotlin.test.Test
import kotlin.test.assertEquals

class HubOrderTest {
    private val defaults = listOf("mood", "qna", "plan", "todo", "calendar", "ideas")

    @Test
    fun `没排过用默认顺序；排过的按存下的，新加的功能接在最后，删掉的功能不出现`() {
        assertEquals(defaults, arrange(defaults, null))
        assertEquals(listOf("todo", "calendar", "mood", "qna", "plan", "ideas"), arrange(defaults, listOf("todo", "calendar", "mood", "gone", "todo")))
    }

    @Test
    fun `按最常用的排：次数多的在前，一样多的保持原来的先后`() {
        val uses = mapOf("todo" to 9, "calendar" to 9, "ideas" to 3)
        assertEquals(listOf("todo", "calendar", "ideas", "mood", "qna", "plan"), byUsage(defaults, uses))
        assertEquals(defaults, byUsage(defaults, emptyMap()))
    }

    @Test
    fun `上下挪一位，到头了不动`() {
        assertEquals(listOf("qna", "mood", "plan", "todo", "calendar", "ideas"), move(defaults, "qna", -1))
        assertEquals(defaults, move(defaults, "mood", -1))
        assertEquals(defaults, move(defaults, "ideas", 1))
        assertEquals(defaults, move(defaults, "unknown", 1))
    }
}
