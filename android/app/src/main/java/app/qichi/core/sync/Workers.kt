package app.qichi.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 发件箱 Worker：有网络时把排队的写操作按顺序发出；发不出去时指数退避重试。 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: OutboxProcessor,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val result = processor.drain()) {
        is OutboxProcessor.Result.Done -> {
            pullQuietly(result.rooms)
            Result.success()
        }
        is OutboxProcessor.Result.Retry -> {
            pullQuietly(result.rooms)
            Result.retry()
        }
        OutboxProcessor.Result.Stop -> Result.failure()
    }

    /** 发完一批后拉取一次，让本地 lastSeq 追上（失败无所谓，下次触发还会拉）。 */
    private suspend fun pullQuietly(rooms: Set<UUID>) {
        for (room in rooms) {
            try {
                syncEngine.pull(room)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

/** 定期同步 Worker：每 15 分钟（有网络时）拉取所有房间；推送做好之前后台靠它。 */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val db: QichiDatabase,
    private val session: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (session.currentUserId == null) return Result.success()
        var failed = false
        for (room in db.syncState().roomIds()) {
            try {
                syncEngine.pull(UUID.fromString(room))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }
}

/** 安排后台工作的唯一入口。 */
class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * 唤起发件箱。
     * @param now true 时取消正在退避等待的任务、立刻发（联网恢复、App 回到前台时）
     */
    fun kickOutbox(now: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        val policy = if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        workManager.enqueueUniqueWork(OUTBOX_WORK, policy, request)
    }

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(OUTBOX_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    private companion object {
        const val OUTBOX_WORK = "qichi-outbox"
        const val PERIODIC_WORK = "qichi-periodic-sync"
    }
}
