package app.qichi.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressTest {
    @Test fun `只在整百分比变了时报：几千次回调最多报 101 次，最后一定是 1`() {
        val reported = mutableListOf<Float>()
        val report = percentProgress { reported += it }
        val total = 20L * 1024 * 1024
        var done = 0L
        while (done < total) {
            done = minOf(total, done + 8 * 1024)
            report(done, total)
        }
        assertEquals(101, reported.size)
        assertEquals(1f, reported.last())
        assertEquals(reported.sorted(), reported)
        report(5, 0) // 不知道总大小时不报
        assertEquals(101, reported.size)
    }
}
