package app.qichi.server.jobs

import app.qichi.server.auth.TokenService
import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.TestDatabase
import app.qichi.server.db.Devices
import app.qichi.server.db.Files
import app.qichi.server.db.Jobs
import app.qichi.server.db.RefreshTokens
import app.qichi.server.db.tx
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Plan
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.rules.DocumentImages
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.imageio.ImageIO
import java.nio.file.Files as NioFiles
import kotlin.io.path.exists
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 每天一次的清理（P13-18）：作废的刷新令牌、旧任务、没人用的文件。 */
class HousekeepingTest {
    private val clock = MutableClock()
    private val ctx = testContext(clock = clock)
    private val db = TestDatabase.database

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun png(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private suspend fun fileRow(id: UUID) = db.tx { Files.selectAll().where { Files.id eq id }.singleOrNull() }

    @Test
    fun `没人用的文件过了宽限期才删：记录、磁盘上的文件和缩略图一起；还在用的一张不动`() = serverTest(ctx) { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val base = "/api/v1/rooms/$roomId"
        suspend fun upload(kind: String = "image") = aqi.upload(roomId, png(), kind = kind).body<FileMeta>()

        // 在用的：消息、文稿里的照片、计划封面、主视觉
        val inMessage = upload()
        aqi.post("$base/messages", SendMessageRequest(UuidV7.generate(), "image", fileId = inMessage.id))
        val inDoc = upload()
        val doc = aqi.post("$base/documents", CreateDocumentRequest(UuidV7.generate(), "游记")).body<Document>()
        aqi.post("$base/documents/${doc.id}/versions", SaveDocumentVersionRequest(UuidV7.generate(), 0, DocumentImages.markdown(inDoc.id)))
        val plan = aqi.post("$base/plans", CreatePlanRequest(UuidV7.generate(), "去海边", aqi.userId())).body<Plan>()
        val oldCover = upload()
        aqi.patch("$base/plans/${plan.id}", UpdatePlanRequest(coverFileId = Patch.of(oldCover.id)))
        val cover = upload()
        // 换了封面：旧的那张没人用了
        val updated = aqi.patch("$base/plans/${plan.id}", UpdatePlanRequest(coverFileId = Patch.of(cover.id)))
        assertEquals(HttpStatusCode.OK, updated.status)
        val hero = upload("hero")
        aqi.patch(base, UpdateRoomRequest(heroFileId = Patch.of(hero.id)))
        // 传上去就没用上（比如消息最后没发出去）
        val abandoned = upload()
        assertEquals(HttpStatusCode.OK, aqi.get("/api/v1/files/${abandoned.id}/thumb?w=200").status)

        val abandonedPath = ctx.files.resolve(fileRow(abandoned.id)!![Files.storagePath])
        val thumb = abandonedPath.resolveSibling("${abandonedPath.fileName}.w200.jpg")
        assertTrue(abandonedPath.exists() && thumb.exists())

        // 还在宽限期内：什么都不删
        clock.advance(Housekeeping.FILE_GRACE.minus(Duration.ofHours(1)))
        assertEquals(0, ctx.housekeeping.run().files)
        assertTrue(fileRow(abandoned.id) != null)
        // 这时又传了一张没用上的
        val fresh = Api(client).loginOk("aqi").upload(roomId, png()).body<FileMeta>()

        clock.advance(Duration.ofHours(2))
        val report = ctx.housekeeping.run()
        assertEquals(2, report.files)
        for (gone in listOf(abandoned, oldCover)) assertEquals(null, fileRow(gone.id), "${gone.id} 应该删掉")
        for (kept in listOf(inMessage, inDoc, cover, hero, fresh)) assertTrue(fileRow(kept.id) != null, "${kept.id} 还在用或还没过宽限期")
        assertFalse(abandonedPath.exists(), "磁盘上的文件也删掉")
        assertFalse(thumb.exists(), "缩略图也删掉")
    }

    @Test
    fun `磁盘上没有记录的文件、临时目录里的半截上传：过了宽限期删掉；别的目录不动`() = serverTest(ctx) { client ->
        val (aqi, _, roomId) = Api(client).pair()
        val kept = aqi.upload(roomId, png()).body<FileMeta>()
        val keptPath = ctx.files.resolve(fileRow(kept.id)!![Files.storagePath])
        val root = ctx.config.filesDir
        val now = clock.instant()
        fun write(relative: String, modified: Instant): java.nio.file.Path {
            val path = root.resolve(relative)
            NioFiles.createDirectories(path.parent)
            NioFiles.write(path, byteArrayOf(1, 2, 3))
            NioFiles.setLastModifiedTime(path, FileTime.from(modified))
            return path
        }
        val old = now.minus(Housekeeping.FILE_GRACE).minus(Duration.ofDays(1))
        // 进程在上传或生成预览途中没了：文件写好了、记录没有
        val strayOld = write("$roomId/2026/01/${UuidV7.generate()}", old)
        val strayThumb = write("$roomId/2026/01/${strayOld.fileName}.w200.jpg", old)
        val strayNew = write("$roomId/2026/01/${UuidV7.generate()}", now)
        // 房间已经不在了
        val goneRoom = write("${UuidV7.generate()}/2025/12/${UuidV7.generate()}", old)
        // 上传到一半
        val partOld = write(".tmp/upload-1.part", now.minus(Duration.ofDays(2)))
        val partNew = write(".tmp/upload-2.part", now)
        // 不是按房间存放的目录
        val release = write("app-releases/qichi-7.apk", old)
        NioFiles.setLastModifiedTime(keptPath, FileTime.from(old))

        val report = ctx.housekeeping.run()
        assertEquals(3, report.strayFiles)
        assertEquals(1, report.tempFiles)
        assertFalse(strayOld.exists())
        assertFalse(strayThumb.exists())
        assertFalse(goneRoom.exists())
        assertFalse(goneRoom.parent.exists(), "空了的目录也删掉")
        assertFalse(partOld.exists())
        for (path in listOf(strayNew, partNew, release, keptPath)) assertTrue(path.exists(), "$path 不该删")
    }

    @Test
    fun `只清理作废很久的令牌和设备，保留闲置登录与旧版到期字段`() = serverTest(ctx) { client ->
        val aqi = Api(client).registerOk("aqi")
        val userId = aqi.userId()
        val now = clock.instant()
        suspend fun token(family: UUID, expires: Instant, revoked: Instant? = null): UUID = db.tx {
            val id = UuidV7.generate()
            RefreshTokens.insert {
                it[RefreshTokens.id] = id
                it[RefreshTokens.userId] = userId
                it[familyId] = family
                it[tokenHash] = id.toString()
                it[expiresAt] = expires
                it[revokedAt] = revoked
                it[createdAt] = expires.minus(Duration.ofDays(60))
            }
            id
        }
        suspend fun device(family: UUID?): UUID = db.tx {
            val id = UuidV7.generate()
            Devices.insert {
                it[Devices.id] = id
                it[Devices.userId] = userId
                it[provider] = "unifiedpush"
                it[token] = "https://push.example.com/$id"
                it[refreshFamilyId] = family
                it[createdAt] = now
                it[updatedAt] = now
            }
            id
        }
        // 早已作废的登录：旧令牌和新版不闲置过期的令牌都按作废时间清理
        val dead = UuidV7.generate()
        val deadOld = token(dead, now.minus(Housekeeping.TOKEN_KEEP).minus(Duration.ofDays(2)), revoked = now.minus(Duration.ofDays(100)))
        val deadLast = token(dead, TokenService.REFRESH_EXPIRES_AT, revoked = now.minus(Duration.ofDays(31)))
        val deadDevice = device(dead)
        // 旧版曾标为到期的令牌：未作废的仍保留，设备也保留
        val expired = UuidV7.generate()
        val expiredToken = token(expired, now.minus(Duration.ofDays(100)))
        val expiredDevice = device(expired)
        // 还在用的登录：换下来的旧令牌没过期，也留着
        val alive = UuidV7.generate()
        val rotated = token(alive, now.plus(Duration.ofDays(10)), revoked = now.minus(Duration.ofDays(1)))
        val current = token(alive, now.plus(Duration.ofDays(59)))
        val aliveDevice = device(alive)
        val legacyDevice = device(null)

        val report = ctx.housekeeping.run()
        assertEquals(2, report.refreshTokens)
        assertEquals(1, report.devices)
        val tokens = db.tx { RefreshTokens.select(RefreshTokens.id).map { it[RefreshTokens.id] }.toSet() }
        assertFalse(deadOld in tokens || deadLast in tokens)
        assertTrue(listOf(expiredToken, rotated, current).all { it in tokens })
        val devices = db.tx { Devices.select(Devices.id).map { it[Devices.id] }.toSet() }
        assertEquals(setOf(aliveDevice, expiredDevice, legacyDevice), devices)
    }

    @Test
    fun `做完的旧任务、失败很久的任务删掉；排队中的不动；清理自己按天排下一次`() = serverTest(ctx) { _ ->
        val now = clock.instant()
        suspend fun job(status: String, updated: Instant, kind: String = "test.kind"): UUID = db.tx {
            val id = UuidV7.generate()
            Jobs.insert {
                it[Jobs.id] = id
                it[Jobs.kind] = kind
                it[payload] = JsonObject(emptyMap())
                it[Jobs.status] = status
                it[runAt] = updated
                it[attempts] = 1
                it[maxAttempts] = 3
                it[createdAt] = updated
                it[updatedAt] = updated
            }
            id
        }
        val oldDone = job(JobQueue.STATUS_DONE, now.minus(Housekeeping.DONE_KEEP).minus(Duration.ofHours(1)))
        val recentDone = job(JobQueue.STATUS_DONE, now.minus(Duration.ofDays(1)))
        val oldFailed = job(JobQueue.STATUS_FAILED, now.minus(Housekeeping.FAILED_KEEP).minus(Duration.ofHours(1)))
        val recentFailed = job(JobQueue.STATUS_FAILED, now.minus(Housekeeping.DONE_KEEP).minus(Duration.ofDays(1)))
        val oldQueued = job(JobQueue.STATUS_QUEUED, now.minus(Duration.ofDays(90)))

        val report = ctx.housekeeping.run()
        assertEquals(2, report.jobs)
        val left = db.tx { Jobs.select(Jobs.id).map { it[Jobs.id] }.toSet() }
        assertEquals(setOf(recentDone, recentFailed, oldQueued), left)
        assertFalse(oldDone in left || oldFailed in left)

        // 启动时排上；跑完排下一次（一天后），不会重复排
        ctx.housekeeping.ensureScheduled()
        ctx.housekeeping.ensureScheduled()
        suspend fun scheduled() = db.tx {
            Jobs.selectAll().where { (Jobs.kind eq Housekeeping.JOB_KIND) }.map { it[Jobs.status] to it[Jobs.runAt] }
        }
        assertEquals(1, scheduled().count { it.first == JobQueue.STATUS_QUEUED })
        ctx.jobs.drain()
        val next = scheduled().filter { it.first == JobQueue.STATUS_QUEUED }
        assertEquals(1, next.size)
        assertEquals(clock.instant().truncatedTo(ChronoUnit.MICROS).plus(Housekeeping.EVERY), next.single().second)
    }
}
