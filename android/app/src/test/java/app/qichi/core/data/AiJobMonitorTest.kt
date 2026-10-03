package app.qichi.core.data

import app.qichi.shared.model.AiJobStatus
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiJobMonitorTest {
    @Test fun `丢失失败通知后仍解除等待`() = runTest {
        var failed = false
        monitorAiJob(UUID.randomUUID(), { !failed }, { AiJobStatus.Failed }, { error("没有成功结果") }, { failed = true })
        assertTrue(failed)
    }

    @Test fun `临时断网后重查同一个任务并同步完成结果`() = runTest {
        val id = UUID.randomUUID()
        var calls = 0
        var pending = true
        monitorAiJob(id, { pending }, {
            assertEquals(id, it)
            if (++calls == 1) throw IOException("offline")
            AiJobStatus.Done
        }, { pending = false }, { error("不能把临时断网当生成失败") })
        assertEquals(2, calls)
    }

    @Test fun `退出页面时取消状态查询`() = runTest {
        assertFailsWith<CancellationException> {
            monitorAiJob(UUID.randomUUID(), { true }, { throw CancellationException() }, {}, {})
        }
    }
}
