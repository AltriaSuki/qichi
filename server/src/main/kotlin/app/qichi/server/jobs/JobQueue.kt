package app.qichi.server.jobs

import app.qichi.server.db.Jobs
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Tx
import app.qichi.server.db.tx
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 领到手的一个任务。[attempts] 已包含这一次。 */
data class QueuedJob(
    val id: UUID,
    val kind: String,
    val payload: JsonObject,
    val attempts: Int,
    val maxAttempts: Int,
) {
    val isLastAttempt: Boolean get() = attempts >= maxAttempts
}

/** 处理函数抛出它表示不必再重试（例如参数本身有问题）。 */
class PermanentJobFailure(message: String) : Exception(message)

typealias JobHandler = suspend (QueuedJob) -> Unit

/**
 * 队列彻底放弃一个任务时调用（最后一次也失败、[PermanentJobFailure]、执行中断的次数用完）：
 * 用来把业务上的状态也标成失败（例如 ai_jobs、审稿预览），免得界面一直停在「进行中」。第二个参数是原因（给日志看）。
 */
typealias GiveUpHandler = suspend (QueuedJob, String) -> Unit

private class Registration(val handler: JobHandler, val onGiveUp: GiveUpHandler?)

private val log = LoggerFactory.getLogger(JobQueue::class.java)

/**
 * 基于 PostgreSQL 的任务队列（docs/02-architecture.md「任务队列」）：AI 请求、文档转换、年度回顾等耗时操作。
 * - 入队和业务写入在同一个事务里，提交后唤醒工作协程
 * - 用 `SELECT … FOR UPDATE SKIP LOCKED` 领取，多个实例也不会重复执行
 * - 处理函数抛异常 = 这次失败：按次数退避重试，用完次数或 [PermanentJobFailure] 则标记失败并调用 [GiveUpHandler]
 * - 执行期间每 [HEARTBEAT] 刷新一次 locked_at；超过 [STALE_AFTER] 没有心跳的「执行中」任务（进程被杀、重启）
 *   由每分钟一次的回收放回队列，次数用完的标失败（P13-03）
 */
class JobQueue(private val db: QichiDatabase, private val clock: Clock) {
    private val handlers = ConcurrentHashMap<String, Registration>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** @param onGiveUp 彻底放弃时的收尾（见 [GiveUpHandler]） */
    fun register(kind: String, onGiveUp: GiveUpHandler? = null, handler: JobHandler) {
        handlers[kind] = Registration(handler, onGiveUp)
    }

    /** 在事务里入队。 */
    fun enqueue(
        tx: Tx,
        kind: String,
        payload: JsonObject,
        id: UUID = UuidV7.generate(),
        maxAttempts: Int = 3,
        delay: Duration = Duration.ZERO,
    ): UUID {
        val now = clock.instant()
        Jobs.insert {
            it[Jobs.id] = id
            it[Jobs.kind] = kind
            it[Jobs.payload] = payload
            it[status] = STATUS_QUEUED
            it[runAt] = now.plus(delay)
            it[attempts] = 0
            it[Jobs.maxAttempts] = maxAttempts
            it[createdAt] = now
            it[updatedAt] = now
        }
        tx.afterCommit { wakeups.trySend(Unit) }
        return id
    }

    /** 领取并执行一个到期的任务；没有到期的任务返回 false。 */
    suspend fun runNext(): Boolean {
        val job = claim() ?: return false
        val registration = handlers[job.kind]
        try {
            if (registration == null) throw PermanentJobFailure("没有处理 ${job.kind} 的程序")
            withHeartbeat(job.id) { registration.handler(job) }
            finish(job.id)
        } catch (e: CancellationException) {
            // 服务停止：放回队列，下次启动再做（协程已经取消，放回去这一步不能再被取消）
            withContext(NonCancellable) { release(job.id) }
            throw e
        } catch (e: Exception) {
            val permanent = e is PermanentJobFailure || job.isLastAttempt
            val reason = e.message ?: e.javaClass.simpleName
            log.warn("任务 {}（{}）第 {} 次失败{}", job.id, job.kind, job.attempts, if (permanent) "，不再重试" else "", e)
            fail(job, reason, permanent)
            if (permanent) giveUp(registration, job, reason)
        }
        return true
    }

    /** 执行 [block] 期间定时刷新任务的 locked_at，证明它还活着。 */
    private suspend fun <T> withHeartbeat(id: UUID, block: suspend () -> T): T = coroutineScope {
        val beat = launch {
            while (isActive) {
                delay(HEARTBEAT.toMillis())
                try {
                    touch(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("任务 {} 的心跳没写进去", id, e)
                }
            }
        }
        try {
            block()
        } finally {
            beat.cancel()
        }
    }

    private suspend fun giveUp(registration: Registration?, job: QueuedJob, reason: String) {
        val onGiveUp = registration?.onGiveUp ?: return
        try {
            onGiveUp(job, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("任务 {}（{}）放弃时的收尾出错", job.id, job.kind, e)
        }
    }

    /** 把到期的任务一个个做完（测试里用，也用于启动时补做）。 */
    suspend fun drain() {
        while (runNext()) Unit
    }

    /**
     * 后台工作协程：有任务就做，没有就等（被入队唤醒，或每 [pollInterval] 看一次到期的定时任务）。
     * 另有一个协程每 [REAP_EVERY] 回收一次没有心跳的「执行中」任务。
     */
    fun start(scope: CoroutineScope, pollInterval: Duration = Duration.ofSeconds(5)) = scope.launch {
        launch {
            while (isActive) {
                try {
                    recoverStale()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("回收中断的任务出错", e)
                }
                delay(REAP_EVERY.toMillis())
            }
        }
        while (isActive) {
            try {
                if (!runNext()) withTimeoutOrNull(pollInterval.toMillis()) { wakeups.receive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("任务队列出错", e)
                delay(pollInterval.toMillis())
            }
        }
    }

    private suspend fun claim(): QueuedJob? = db.tx {
        val now = clock.instant()
        val row = Jobs.selectAll()
            .where { (Jobs.status eq STATUS_QUEUED) and (Jobs.runAt lessEq now) }
            .orderBy(Jobs.runAt, SortOrder.ASC)
            .limit(1)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .singleOrNull() ?: return@tx null
        val attempts = row[Jobs.attempts] + 1
        Jobs.update({ Jobs.id eq row[Jobs.id] }) {
            it[status] = STATUS_RUNNING
            it[Jobs.attempts] = attempts
            it[lockedAt] = now
            it[updatedAt] = now
        }
        QueuedJob(row[Jobs.id], row[Jobs.kind], row[Jobs.payload], attempts, row[Jobs.maxAttempts])
    }

    private suspend fun finish(id: UUID) = db.tx {
        Jobs.update({ Jobs.id eq id }) {
            it[status] = STATUS_DONE
            it[lockedAt] = null
            it[lastError] = null
            it[updatedAt] = clock.instant()
        }
    }

    private suspend fun fail(job: QueuedJob, error: String, permanent: Boolean) = db.tx {
        val now = clock.instant()
        Jobs.update({ Jobs.id eq job.id }) {
            it[status] = if (permanent) STATUS_FAILED else STATUS_QUEUED
            if (!permanent) it[runAt] = now.plus(backoff(job.attempts))
            it[lockedAt] = null
            it[lastError] = error.take(1000)
            it[updatedAt] = now
        }
    }

    private suspend fun release(id: UUID) = db.tx {
        Jobs.update({ Jobs.id eq id }) {
            it[status] = STATUS_QUEUED
            it[lockedAt] = null
        }
    }

    private suspend fun touch(id: UUID) = db.tx {
        Jobs.update({ (Jobs.id eq id) and (Jobs.status eq STATUS_RUNNING) }) {
            it[lockedAt] = clock.instant()
        }
    }

    /**
     * 「执行中」但超过 [STALE_AFTER] 没有心跳的任务（进程被杀、重启、卡死）：还有次数的放回队列马上再做，
     * 次数用完的标失败并调用收尾（一个每次都让进程崩掉的任务不会无限重来）。返回处理了几个。
     */
    internal suspend fun recoverStale(): Int {
        val (requeued, dead) = db.tx {
            val now = clock.instant()
            val stale = Jobs.selectAll()
                .where { (Jobs.status eq STATUS_RUNNING) and (Jobs.lockedAt less now.minus(STALE_AFTER)) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { QueuedJob(it[Jobs.id], it[Jobs.kind], it[Jobs.payload], it[Jobs.attempts], it[Jobs.maxAttempts]) }
            val (dead, alive) = stale.partition { it.isLastAttempt }
            for (job in alive) {
                Jobs.update({ Jobs.id eq job.id }) {
                    it[status] = STATUS_QUEUED
                    it[lockedAt] = null
                    it[runAt] = now
                    it[updatedAt] = now
                }
            }
            for (job in dead) {
                Jobs.update({ Jobs.id eq job.id }) {
                    it[status] = STATUS_FAILED
                    it[lockedAt] = null
                    it[lastError] = INTERRUPTED
                    it[updatedAt] = now
                }
            }
            alive to dead
        }
        for (job in requeued) log.warn("任务 {}（{}）执行中断，放回队列", job.id, job.kind)
        for (job in dead) {
            log.warn("任务 {}（{}）执行中断 {} 次，不再重试", job.id, job.kind, job.attempts)
            giveUp(handlers[job.kind], job, INTERRUPTED)
        }
        if (requeued.isNotEmpty()) wakeups.trySend(Unit)
        return requeued.size + dead.size
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        /** 执行中每隔多久刷新一次 locked_at */
        val HEARTBEAT: Duration = Duration.ofSeconds(30)

        /** 多久没有心跳就算中断了（错过 4 次心跳） */
        val STALE_AFTER: Duration = Duration.ofMinutes(2)

        /** 多久回收一次中断的任务 */
        private val REAP_EVERY: Duration = Duration.ofMinutes(1)

        private const val INTERRUPTED = "执行中断（服务重启或卡住）"

        /** 第 n 次失败后等多久再试：5 秒、10 秒、20 秒……最多 10 分钟。 */
        fun backoff(attempts: Int): Duration =
            Duration.ofSeconds(5L shl (attempts - 1).coerceIn(0, 7)).coerceAtMost(Duration.ofMinutes(10))
    }
}
