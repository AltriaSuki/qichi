package app.qichi.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** 仓库的 Flow 解析、整理在后台线程做，收集方（界面线程）只拿结果（P17-01）。 */
class OffMainTest {
    @Test
    fun `接在 offMain 前面的整理不在收集方的线程上跑`() = runBlocking {
        val collector = Thread.currentThread()
        var worker: Thread? = null
        val result = flowOf(listOf(3, 1, 2))
            .map { worker = Thread.currentThread(); it.sorted() }
            .offMain()
            .first()
        assertEquals(listOf(1, 2, 3), result)
        assertNotEquals(collector, worker)
    }
}
