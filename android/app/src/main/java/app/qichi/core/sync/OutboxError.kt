package app.qichi.core.sync

import app.qichi.shared.api.QichiJson
import kotlinx.serialization.Serializable

/**
 * 一条发件箱记录的出错情况（P13-10），存在 outbox.lastError 列里（JSON，不改数据库结构）。
 *
 * 服务器一直出错（5xx、429）的一条，从第一次出错算起累计满 [SERVER_ERROR_LIMIT_MS]，就标「发送失败」，让后面的接着发；
 * 断网不算：只有相邻两次都是服务器错误，中间的时间才计入 [failingMs]，中间断过网的那段不算（人类 2026-09-26 选定）。
 *
 * @property reason 最近一次的原因（排查用）
 * @property failingMs 遇到服务器错误的累计时长
 * @property lastServerErrorAt 上一次是服务器错误的时间；上一次是断网、或还没出过服务器错误时为空
 */
@Serializable
data class OutboxError(
    val reason: String? = null,
    val failingMs: Long = 0,
    val lastServerErrorAt: Long? = null,
) {
    fun afterServerError(reason: String, now: Long): OutboxError =
        OutboxError(reason, failingMs + (lastServerErrorAt?.let { (now - it).coerceAtLeast(0) } ?: 0), now)

    fun afterNetworkError(reason: String): OutboxError = copy(reason = reason, lastServerErrorAt = null)

    /** 服务器错误累计够久了，放弃这一条 */
    val gaveUp: Boolean get() = failingMs >= SERVER_ERROR_LIMIT_MS

    fun encode(): String = QichiJson.encodeToString(serializer(), this)

    companion object {
        const val SERVER_ERROR_LIMIT_MS = 24 * 60 * 60 * 1000L

        /** 以前记的是一句话（或者为空）：当作还没开始计时。 */
        fun parse(text: String?): OutboxError =
            text?.let { runCatching { QichiJson.decodeFromString(serializer(), it) }.getOrNull() } ?: OutboxError(reason = text)
    }
}
