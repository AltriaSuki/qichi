package app.qichi.server.jobs

import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.db.Jobs
import app.qichi.server.db.tx
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
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

    /** 一个「执行中」的任务，最后一次心跳在 [lockedAgo] 之前（模拟进程被杀时留下的）。 */
    private suspend fun insertRunning(kind: String, lockedAgo: Duration, attempts: Int, maxAttempts: Int): UUID = db.tx {
        val id = UuidV7.generate()
        val now = clock.instant()
        Jobs.insert {
            it[Jobs.id] = id
            it[Jobs.kind] = kind
            it[payload] = JsonObject(emptyMap())
            it[status] = JobQueue.STATUS_RUNNING
            it[runAt] = now.minus(lockedAgo)
            it[Jobs.attempts] = attempts
            it[Jobs.maxAttempts] = maxAttempts
            it[lockedAt] = now.minus(lockedAgo)
            it[createdAt] = now
            it[updatedAt] = now
        }
        id
    }

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
    fun `执行中断（心跳停了）的任务放回队列重新执行；还在心跳的不动`() = runBlocking {
        var runs = 0
        queue.register("work") { runs++ }
        val stale = insertRunning("work", lockedAgo = JobQueue.STALE_AFTER.plusSeconds(1), attempts = 1, maxAttempts = 3)
        val alive = insertRunning("work", lockedAgo = JobQueue.HEARTBEAT, attempts = 1, maxAttempts = 3)
        assertEquals(1, queue.recoverStale())
        assertEquals(JobQueue.STATUS_QUEUED, status(stale)[Jobs.status])
        assertEquals(JobQueue.STATUS_RUNNING, status(alive)[Jobs.status])
        queue.drain()
        assertEquals(1, runs)
        assertEquals(JobQueue.STATUS_DONE, status(stale)[Jobs.status])
        assertEquals(2, status(stale)[Jobs.attempts])
    }

    @Test
    fun `中断次数用完：标失败并调用收尾，不再重来`() = runBlocking {
        val gaveUp = mutableListOf<UUID>()
        queue.register("crashy", { job, _ -> gaveUp += job.id }) { error("不该再执行") }
        val id = insertRunning("crashy", lockedAgo = Duration.ofMinutes(5), attempts = 2, maxAttempts = 2)
        assertEquals(1, queue.recoverStale())
        assertEquals(JobQueue.STATUS_FAILED, status(id)[Jobs.status])
        assertEquals(listOf(id), gaveUp)
        assertFalse(queue.runNext())
    }

    @Test
    fun `最后一次失败时调用收尾，还能重试时不调用`() = runBlocking {
        val gaveUp = mutableListOf<String>()
        queue.register("flaky", { _, reason -> gaveUp += reason }) { error("还是不行") }
        val id = enqueue("flaky", maxAttempts = 2)
        queue.drain()
        assertTrue(gaveUp.isEmpty(), "还能重试")
        clock.advance(JobQueue.backoff(1))
        queue.drain()
        assertEquals(listOf("还是不行"), gaveUp)
        assertEquals(JobQueue.STATUS_FAILED, status(id)[Jobs.status])
    }

    @Test
    fun `服务停止时手上的任务放回队列`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        queue.register("slow") {
            started.complete(Unit)
            awaitCancellation()
        }
        val id = enqueue("slow")
        val worker = launch { queue.runNext() }
        started.await()
        worker.cancelAndJoin()
        assertEquals(JobQueue.STATUS_QUEUED, status(id)[Jobs.status])
    }

    @Test
    fun `退避时间：5 秒起翻倍，最多 10 分钟`() {
        assertEquals(Duration.ofSeconds(5), JobQueue.backoff(1))
        assertEquals(Duration.ofSeconds(10), JobQueue.backoff(2))
        assertEquals(Duration.ofMinutes(10), JobQueue.backoff(20))
        assertTrue(JobQueue.backoff(3) > JobQueue.backoff(2))
    }
}
