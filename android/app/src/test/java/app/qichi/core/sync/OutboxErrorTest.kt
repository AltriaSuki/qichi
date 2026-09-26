package app.qichi.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 发件箱一条记录的出错计时（P13-10）：服务器错误累计 24 小时放弃，断网的那段不算。 */
class OutboxErrorTest {
    private val hour = 3_600_000L

    @Test
    fun `相邻两次服务器错误之间的时间才算，断网打断的那段不算`() {
        var e = OutboxError.parse(null).afterServerError("500", 0)
        assertEquals(0, e.failingMs)
        e = e.afterServerError("500", 10 * hour)
        assertEquals(10 * hour, e.failingMs)
        e = e.afterNetworkError("网络不可用")
        e = e.afterServerError("500", 30 * hour)
        assertEquals(10 * hour, e.failingMs, "断网到下一次服务器错误之间不算")
        assertFalse(e.gaveUp)
        e = e.afterServerError("503", 44 * hour)
        assertEquals(24 * hour, e.failingMs)
        assertTrue(e.gaveUp)
        assertEquals("503", e.reason)
    }

    @Test
    fun `存进 lastError 再读回来不变；以前记的一句话当作还没开始计时`() {
        val e = OutboxError("500 internal_error", 5, 7)
        assertEquals(e, OutboxError.parse(e.encode()))
        assertEquals(OutboxError(reason = "500 internal_error"), OutboxError.parse("500 internal_error"))
        assertEquals(OutboxError(), OutboxError.parse(null))
    }
}
