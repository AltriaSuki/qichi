package app.qichi.shared.util

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * UUIDv7（RFC 9562）：前 48 位是 Unix 毫秒时间戳，所以按生成时间有序，便于排序和建索引。
 *
 * 同一毫秒内用 12 位 `rand_a` 作计数器（RFC 9562 §6.2 方法 1），保证同一进程里严格递增；
 * 计数器用完或系统时钟回拨时，时间戳借用上一个值继续递增，不会生成更小的 id。
 */
object UuidV7 {
    private val default = Generator(System::currentTimeMillis, SecureRandom())

    /** 生成一个新的 UUIDv7。线程安全。 */
    fun generate(): UUID = default.next()

    /** 取出 UUIDv7 里的毫秒时间戳。 */
    fun timestampMillis(uuid: UUID): Long {
        require(uuid.version() == 7) { "不是 UUIDv7：$uuid" }
        return uuid.mostSignificantBits ushr 16
    }

    /** 可注入时钟和随机源的生成器，测试用。 */
    class Generator(
        private val clock: () -> Long,
        private val random: Random,
    ) {
        private var lastMillis = Long.MIN_VALUE
        private var counter = 0

        @Synchronized
        fun next(): UUID {
            var millis = clock()
            if (millis > lastMillis) {
                // 新的一毫秒：计数器从随机值开始，最高位留 0，给同一毫秒内的递增留空间
                counter = random.nextInt(COUNTER_SEED_BOUND)
            } else {
                // 同一毫秒或时钟回拨：沿用上一个时间戳，计数器加一
                millis = lastMillis
                counter++
                if (counter > COUNTER_MAX) {
                    millis++
                    counter = 0
                }
            }
            lastMillis = millis

            val msb = (millis shl 16) or VERSION_BITS or counter.toLong()
            val lsb = (random.nextLong() and RAND_B_MASK) or VARIANT_BITS
            return UUID(msb, lsb)
        }
    }

    private const val COUNTER_MAX = 0xFFF
    private const val COUNTER_SEED_BOUND = 0x800
    private const val VERSION_BITS = 0x7000L
    private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT_BITS = Long.MIN_VALUE // 二进制 10xx…，RFC 9562 变体
}
