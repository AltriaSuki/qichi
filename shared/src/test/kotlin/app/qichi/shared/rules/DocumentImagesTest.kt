package app.qichi.shared.rules

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentImagesTest {
    private val a = UUID.fromString("01a0c31e-bdff-73fd-ba1e-305adb764467")
    private val b = UUID.fromString("01a0d252-2b35-7ae4-add9-b674de53834b")

    @Test
    fun `写出的标记能认回来；一整行才算图片行`() {
        val md = DocumentImages.markdown(a)
        assertEquals("![照片](qichi-file:$a)", md)
        val m = DocumentImages.line.find(md)!!
        assertEquals("照片", m.groupValues[1])
        assertEquals(a.toString(), m.groupValues[2])
        assertTrue(DocumentImages.line.find("前面有字 $md") == null)
        assertEquals("![海边]", DocumentImages.markdown(a, "海]边").substringBefore("("), "说明里的 ] 去掉")
    }

    @Test
    fun `找出引用的文件、按顺序去重；可以整体替换`() {
        val body = "第一段\n\n${DocumentImages.markdown(a)}\n\n${DocumentImages.markdown(b, "日落")}\n\n${DocumentImages.markdown(a)}"
        assertEquals(listOf(a, b), DocumentImages.fileIds(body))
        val out = DocumentImages.rewrite(body) { alt, id -> if (id == a) "（$alt）" else "![$alt](../附件/sunset.jpg)" }
        assertEquals("第一段\n\n（照片）\n\n![日落](../附件/sunset.jpg)\n\n（照片）", out)
    }
}
