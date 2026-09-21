package app.qichi.server.auth

import app.qichi.server.plugins.ApiException
import app.qichi.shared.model.ProblemCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 失败次数限流：同一个 key 在 [window] 内失败 [maxFailures] 次后，直到最早那次失败过了 [window] 才能再试。
 * 用于登录（按用户名）、改密码（按用户）、邀请码（按 IP）。只在内存里，服务端重启后清零。
 */
class FailureThrottle(
    private val clock: Clock,
    private val maxFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** 超过次数时抛出 429 rate_limited（带 Retry-After）。 */
    fun check(key: String) {
        val now = clock.instant()
        val list = failures[key] ?: return
        synchronized(list) {
            prune(list, now)
            if (list.size >= maxFailures) {
                val retryAfter = Duration.between(now, list.first().plus(window)).seconds.coerceAtLeast(1)
                throw ApiException(
                    ProblemCode.RateLimited,
                    "尝试次数太多，请稍后再试",
                    retryAfterSeconds = retryAfter,
                )
            }
        }
    }

    fun recordFailure(key: String) {
        val now = clock.instant()
        val list = failures.computeIfAbsent(key) { ArrayDeque() }
        synchronized(list) {
            prune(list, now)
            list.addLast(now)
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    private fun prune(list: ArrayDeque<Instant>, now: Instant) {
        while (list.isNotEmpty() && !list.first().plus(window).isAfter(now)) list.removeFirst()
    }
}
