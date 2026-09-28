package app.qichi.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiFailTextTest {
    @Test
    fun `各种原因说清楚；额度用完和太长不给重试`() {
        assertEquals(AiFailText("这个月的 AI 额度用完了，下个月 1 号恢复", canRetry = false), aiFailText("quota"))
        assertTrue(aiFailText("unreachable").text.contains("连不上"))
        assertTrue(aiFailText("unreachable").canRetry)
        assertTrue(aiFailText("provider").text.contains("AI 服务出错"))
        assertTrue(aiFailText("provider").canRetry)
        assertFalse(aiFailText("too_long").canRetry)
        assertTrue(aiFailText("too_long").text.contains("太长"))
    }

    @Test
    fun `旧服务端没给原因、或是不认识的新原因：照旧说没有得到回答，能重试`() {
        assertEquals(AiFailText("没有得到回答", canRetry = true), aiFailText(null))
        assertEquals(AiFailText("没有得到回答", canRetry = true), aiFailText("something_new"))
        assertEquals(AiFailText("没有得到回答", canRetry = true), aiFailText("other"))
    }
}
