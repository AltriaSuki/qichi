package app.qichi.core.data

import org.junit.Test
import kotlin.test.assertEquals

class DocumentPreviewTest {
    @Test
    fun `预览去掉标题、列表符号、勾选框、照片，空行跳过，最多 80 字`() {
        val body = "## 海边周末\n\n- 周六早上八点出发\n- 去东山岛\n- [ ] 带相机\n1. 看日出\n> 慢慢走\n![](qichi-file:00000000-0000-0000-0000-000000000001)\n**好**"
        assertEquals("周六早上八点出发 去东山岛 带相机 看日出 慢慢走 好", documentPreview(body))
        assertEquals(80, documentPreview("字".repeat(200)).length)
    }
}
