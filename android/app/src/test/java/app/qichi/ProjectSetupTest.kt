package app.qichi

import app.qichi.shared.util.CjkText
import app.qichi.shared.util.UuidV7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 确认 App 能用上 shared 模块，服务器地址从 local.properties 读进了 BuildConfig。 */
class ProjectSetupTest {

    @Test
    fun `可以使用共享模块`() {
        assertEquals(2, CjkText.charCount("栖迟"))
        assertEquals(7, UuidV7.generate().version())
    }

    @Test
    fun `服务器地址来自 local properties`() {
        assertTrue(BuildConfig.BASE_URL.startsWith("http"), BuildConfig.BASE_URL)
        assertTrue(!BuildConfig.BASE_URL.endsWith("/"))
    }
}
