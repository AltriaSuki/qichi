package app.qichi.server.jobs

import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.db.Jobs
import app.qichi.server.db.tx
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Duration
import java.util.Collections
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobQueueTest {
    private val db = TestDatabase.database
    private val clock = MutableClock()
    private val queue = JobQueue(db, clock)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun enqueue(kind: String, payload: JsonObject = JsonObject(emptyMap()), maxAttempts: Int = 3): UUID =
        db.tx { queue.enqueue(this, kind, payload, maxAttempts = maxAttempts) }

    private suspend fun status(id: UUID) = db.tx { Jobs.selectAll().where { Jobs.id eq id }.single() }

    @Test
    fun `入队的任务被执行一次，做完标记 done`() = runBlocking {
        val seen = mutableListOf<String>()
        queue.register("echo") { job -> seen += job.payload["text"]!!.jsonPrimitive.content }
        val id = enqueue("echo", buildJsonObject { put("text", "你好") })
        queue.drain()
        assertEquals(listOf("你好"), seen)
        assertEquals(JobQueue.STATUS_DONE, status(id)[Jobs.status])
        assertFalse(queue.runNext(), "没有别的任务了")
    }

    @Test
    fun `失败后退避重试，次数用完标记 failed`() = runBlocking {
        var calls = 0
        queue.register("flaky") { calls++; error("暂时不行") }
        val id = enqueue("flaky", maxAttempts = 2)

        queue.drain()
        assertEquals(1, calls)
        assertEquals(JobQueue.STATUS_QUEUED, status(id)[Jobs.status])
        assertEquals("暂时不行", status(id)[Jobs.lastError])
        queue.drain()
        assertEquals(1, calls, "退避时间没到，不会马上重试")

        clock.advance(JobQueue.backoff(1))
        queue.drain()
        assertEquals(2, calls)
        assertEquals(JobQueue.STATUS_FAILED, status(id)[Jobs.status])
        assertEquals(2, status(id)[Jobs.attempts])
    }

    @Test
    fun `PermanentJobFailure 不再重试；没有处理程序的任务直接失败`() = runBlocking {
        queue.register("bad") { throw PermanentJobFailure("参数有问题") }
        val bad = enqueue("bad")
        val unknown = enqueue("nobody-handles-this")
        queue.drain()
        assertEquals(JobQueue.STATUS_FAILED, status(bad)[Jobs.status])
        assertEquals(1, status(bad)[Jobs.attempts])
        assertEquals(JobQueue.STATUS_FAILED, status(unknown)[Jobs.status])
    }

    @Test
    fun `多个工作者同时领取，每个任务只执行一次`() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<Int>())
        queue.register("n") { job ->
            delay(20)
            done += job.payload["n"]!!.jsonPrimitive.content.toInt()
        }
        repeat(10) { n -> enqueue("n", buildJsonObject { put("n", n) }) }
        coroutineScope { (1..4).map { async { queue.drain() } }.awaitAll() }
        assertEquals((0 until 10).toList(), done.sorted())
    }

    @Test
    fun `退避时间：5 秒起翻倍，最多 10 分钟`() {
        assertEquals(Duration.ofSeconds(5), JobQueue.backoff(1))
        assertEquals(Duration.ofSeconds(10), JobQueue.backoff(2))
        assertEquals(Duration.ofMinutes(10), JobQueue.backoff(20))
        assertTrue(JobQueue.backoff(3) > JobQueue.backoff(2))
    }
}
