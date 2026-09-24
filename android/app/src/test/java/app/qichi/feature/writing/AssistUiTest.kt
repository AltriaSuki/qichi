package app.qichi.feature.writing

import org.junit.Test
import kotlin.test.assertEquals

class AssistUiTest {
    @Test
    fun `起标题的结果：每行一个，去掉编号和引号，空行不要`() {
        assertEquals(
            listOf("周末去东山岛看日落", "海风吹过", "下次还来"),
            titleChoices("1. 周末去东山岛看日落\n\n2、“海风吹过”\n- 《下次还来》\n"),
        )
    }
}
