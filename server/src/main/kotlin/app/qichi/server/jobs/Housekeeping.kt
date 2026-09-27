package app.qichi.server.jobs

import app.qichi.server.db.Devices
import app.qichi.server.db.Files
import app.qichi.server.db.Jobs
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RefreshTokens
import app.qichi.server.db.tx
import app.qichi.server.files.FileService
import app.qichi.server.files.FileStorage
import app.qichi.server.rooms.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(Housekeeping::class.java)

/**
 * 每天一次的清理（P13-18）：不再有用、却一直留着的数据。各项互不影响，一项出错只记日志，其余照做。
 *
 * - 刷新令牌：过期超过 [TOKEN_KEEP] 的删掉。过期的本来就不能用；多留一阵，拿旧令牌来的仍按「重复使用」处理。
 *   整次登录都已过期（没有一个还能用的令牌）的推送设备也删掉：那台手机已经登不上了，不再给它发通知。
 * - 任务队列：做完超过 [DONE_KEEP]、失败超过 [FAILED_KEEP] 的记录删掉（结果都在各自的表里；ai_jobs 记着用量，不动）。
 * - 文件：上传超过 [FILE_GRACE] 仍没有任何地方在用的（见 [FileService.inUse]），删记录和磁盘上的文件、缩略图；
 *   磁盘上没有记录、超过 [FILE_GRACE] 没动过的文件（上传或生成预览到一半进程没了、房间没了）删掉；
 *   临时目录里超过 [TEMP_KEEP] 的半截上传删掉。宽限期远长于发件箱补发的时间：先传文件、后发消息，中间可能离线好几天。
 */
class Housekeeping(
    private val db: QichiDatabase,
    private val files: FileService,
    private val storage: FileStorage,
    private val queue: JobQueue,
    private val clock: Clock,
) {
    /** 每一项清掉了多少（日志、测试用）。 */
    data class Report(
        val refreshTokens: Int = 0,
        val devices: Int = 0,
        val jobs: Int = 0,
        val files: Int = 0,
        val strayFiles: Int = 0,
        val tempFiles: Int = 0,
    )

    init {
        queue.register(JOB_KIND, onGiveUp = { _, _ -> ensureScheduled() }) { _ -> runAndReschedule() }
    }

    /** 启动时调用：保证队列里有一个清理任务。 */
    suspend fun ensureScheduled() = db.tx {
        val queued = Jobs.select(Jobs.id)
            .where { (Jobs.kind eq JOB_KIND) and (Jobs.status inList listOf(JobQueue.STATUS_QUEUED, JobQueue.STATUS_RUNNING)) }
            .any()
        if (!queued) queue.enqueue(this, JOB_KIND, buildJsonObject { }, maxAttempts = 1)
    }

    private suspend fun runAndReschedule() {
        try {
            val report = run()
            log.info("每日清理：{}", report)
        } finally {
            db.tx { queue.enqueue(this, JOB_KIND, buildJsonObject { }, maxAttempts = 1, delay = EVERY) }
        }
    }

    suspend fun run(): Report {
        val now = clock.instant()
        var report = Report()
        step("刷新令牌") { purgeTokens(now).also { (tokens, devices) -> report = report.copy(refreshTokens = tokens, devices = devices) } }
        step("任务记录") { report = report.copy(jobs = purgeJobs(now)) }
        step("没人用的文件") { report = report.copy(files = purgeUnusedFiles(now.minus(FILE_GRACE))) }
        step("没有记录的文件") { report = report.copy(strayFiles = purgeStrayFiles(now.minus(FILE_GRACE))) }
        step("半截上传") { report = report.copy(tempFiles = withContext(Dispatchers.IO) { storage.cleanTemp(now.minus(TEMP_KEEP)) }) }
        return report
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("每日清理（{}）出错", name, e)
        }
    }

    private suspend fun purgeTokens(now: Instant): Pair<Int, Int> = db.tx {
        val tokens = RefreshTokens.deleteWhere { RefreshTokens.expiresAt less now.minus(TOKEN_KEEP) }
        val alive = RefreshTokens.select(RefreshTokens.familyId)
            .where { RefreshTokens.revokedAt.isNull() and (RefreshTokens.expiresAt greater now) }
        val devices = Devices.deleteWhere { Devices.refreshFamilyId.isNotNull() and (Devices.refreshFamilyId notInSubQuery alive) }
        tokens to devices
    }

    private suspend fun purgeJobs(now: Instant): Int = db.tx {
        Jobs.deleteWhere {
            ((Jobs.status eq JobQueue.STATUS_DONE) and (Jobs.updatedAt less now.minus(DONE_KEEP))) or
                ((Jobs.status eq JobQueue.STATUS_FAILED) and (Jobs.updatedAt less now.minus(FAILED_KEEP)))
        }
    }

    /** 先一次筛出够老、外键上没人引用的，再按房间锁住房间逐个确认（文稿正文里的照片也看）后删掉。 */
    private suspend fun purgeUnusedFiles(before: Instant): Int {
        val candidates = db.tx(readOnly = true) { files.unreferencedBefore(before) }
        var count = 0
        for ((roomId, ids) in candidates.groupBy({ it.second }, { it.first })) {
            val paths = db.tx {
                // 和房间里的写入排队：确认期间不会有新的消息、封面、主视觉用上这些文件
                RoomRepository.lockRoom(roomId)
                ids.mapNotNull { files.releaseIfUnused(it) }
            }
            files.deleteStored(paths)
            count += paths.size
        }
        return count
    }

    /** 磁盘上有、数据库里没有记录的文件（缩略图跟着原文件算）。最后修改晚于 [before] 的不动：可能正在上传或生成。 */
    private suspend fun purgeStrayFiles(before: Instant): Int {
        var count = 0
        for (roomId in withContext(Dispatchers.IO) { storage.roomDirs() }) {
            val known = db.tx(readOnly = true) {
                Files.select(Files.storagePath).where { Files.roomId eq roomId }.map { it[Files.storagePath] }.toHashSet()
            }
            withContext(Dispatchers.IO) {
                for (entry in storage.filesIn(roomId)) {
                    if (!entry.modified.isBefore(before)) continue
                    // 缩略图：{文件 id}.w200.jpg
                    val original = entry.path.substringBeforeLast('/') + "/" + entry.path.substringAfterLast('/').substringBefore('.')
                    if (original in known) continue
                    storage.delete(entry.path)
                    count++
                }
                storage.pruneEmpty(roomId)
            }
        }
        return count
    }

    companion object {
        const val JOB_KIND = "housekeeping"

        val EVERY: Duration = Duration.ofDays(1)
        val TOKEN_KEEP: Duration = Duration.ofDays(30)
        val DONE_KEEP: Duration = Duration.ofDays(7)
        val FAILED_KEEP: Duration = Duration.ofDays(30)
        val FILE_GRACE: Duration = Duration.ofDays(30)
        val TEMP_KEEP: Duration = Duration.ofDays(1)
    }
}
