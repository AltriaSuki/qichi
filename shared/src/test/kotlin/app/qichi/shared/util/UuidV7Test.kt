package app.qichi.shared.util

import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UuidV7Test {

    @Test
    fun `版本号是 7，变体是 RFC 9562`() {
        repeat(1_000) {
            val id = UuidV7.generate()
            assertEquals(7, id.version())
            assertEquals(2, id.variant())
        }
    }

    @Test
    fun `时间戳可以取回`() {
        val generator = UuidV7.Generator(clock = { 1_790_000_000_123L }, random = Random(1))
        assertEquals(1_790_000_000_123L, UuidV7.timestampMillis(generator.next()))
    }

    @Test
    fun `同一毫秒内连续生成 1 万个也严格递增`() {
        val generator = UuidV7.Generator(clock = { 1_790_000_000_000L }, random = Random(42))
        val ids = List(10_000) { generator.next() }
        assertStrictlyIncreasing(ids)
    }

    @Test
    fun `时钟回拨时不会生成更小的 id`() {
        var now = 1_790_000_000_000L
        val generator = UuidV7.Generator(clock = { now }, random = Random(7))
        val ids = mutableListOf<UUID>()
        repeat(100) { ids += generator.next() }
        now -= 5_000
        repeat(100) { ids += generator.next() }
        assertStrictlyIncreasing(ids)
    }

    @Test
    fun `时间向前走时按时间排序`() {
        var now = 1_790_000_000_000L
        val generator = UuidV7.Generator(clock = { now }, random = Random(3))
        val ids = List(1_000) { generator.next().also { now += 1 } }
        assertStrictlyIncreasing(ids)
        assertEquals(1_790_000_000_000L, UuidV7.timestampMillis(ids.first()))
    }

    @Test
    fun `多线程生成不重复`() {
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
        val threads = List(8) {
            Thread { repeat(5_000) { ids += UuidV7.generate() } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(40_000, ids.size)
    }

    @Test
    fun `不是 UUIDv7 时拒绝取时间戳`() {
        assertFailsWith<IllegalArgumentException> { UuidV7.timestampMillis(UUID.randomUUID()) }
    }

    private fun assertStrictlyIncreasing(ids: List<UUID>) {
        ids.zipWithNext().forEachIndexed { i, (a, b) ->
            // 字符串顺序（数据库、日志里看到的顺序）和数值顺序都要递增
            assertTrue(a.toString() < b.toString(), "第 $i 个之后没有递增：$a ≥ $b")
            assertTrue(a < b, "第 $i 个之后 compareTo 没有递增：$a ≥ $b")
        }
    }
}
