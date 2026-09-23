package app.qichi.server.timeline

import app.qichi.server.db.Decisions
import app.qichi.server.db.Files
import app.qichi.server.db.Ideas
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Rooms
import app.qichi.server.db.TimelinePicks
import app.qichi.server.db.tx
import app.qichi.server.files.toFileMeta
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.api.TimelineMonthCount
import app.qichi.shared.api.TimelinePage
import app.qichi.shared.api.TimelinePick
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.TimelineEntryKind
import app.qichi.shared.model.wireName
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 共同时间线（P6-03）：不单独存数据，按房间时区的月份把定下的决定、记下的灵感、完成的计划、
 * 两个人都选中的照片拼在一起。照片由两个人各自选中（timeline_picks），两人都选了才出现。
 */
class TimelineService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val clock: Clock,
) {
    private data class Raw(val entry: TimelineEntry)

    suspend fun month(userId: UUID, roomId: UUID, year: Int?, month: Int?): TimelinePage {
        validate {
            check((year == null) == (month == null), "month", "年和月要一起给")
            check(year == null || year in 2000..2100, "year", "年份不对")
            check(month == null || month in 1..12, "month", "月份不对")
        }
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val zone = Rooms.select(Rooms.timezone).where { Rooms.id eq roomId }.single()[Rooms.timezone].let { runCatching { ZoneId.of(it) }.getOrDefault(ZoneId.of("Asia/Shanghai")) }
            val all = entries(roomId)
            fun ym(at: Instant) = YearMonth.from(at.atZone(zone))
            val months = all.groupingBy { ym(it.at) }.eachCount()
                .entries.sortedByDescending { it.key }.map { TimelineMonthCount(it.key.year, it.key.monthValue, it.value) }
            val target = if (year != null) YearMonth.of(year, month!!) else months.firstOrNull()?.let { YearMonth.of(it.year, it.month) } ?: YearMonth.from(clock.instant().atZone(zone))
            TimelinePage(target.year, target.monthValue, all.filter { ym(it.at) == target }.sortedBy { it.at }, months)
        }
    }

    /** 房间里所有能上时间线的事（两个人的房间，量不大，一次取出再按月分）。 */
    private fun entries(roomId: UUID): List<TimelineEntry> = buildList {
        Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() and Decisions.decidedAt.isNotNull() }.forEach {
            add(TimelineEntry(TimelineEntryKind.Decision, it[Decisions.id], it[Decisions.decidedAt]!!, it[Decisions.question], it[Decisions.finalChoice], it[Decisions.decidedBy]))
        }
        Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.forEach {
            add(TimelineEntry(TimelineEntryKind.Idea, it[Ideas.id], it[Ideas.createdAt], it[Ideas.body], null, it[Ideas.authorId]))
        }
        Plans.selectAll().where {
            (Plans.roomId eq roomId) and Plans.deletedAt.isNull() and (Plans.status eq PlanStatus.Done.wireName) and Plans.completedAt.isNotNull()
        }.forEach {
            add(TimelineEntry(TimelineEntryKind.Plan, it[Plans.id], it[Plans.completedAt]!!, it[Plans.title], it[Plans.completionNote], it[Plans.ownerId]))
        }
        val bothPicked = TimelinePicks.select(TimelinePicks.fileId, TimelinePicks.userId).where { TimelinePicks.roomId eq roomId }
            .groupBy({ it[TimelinePicks.fileId] }, { it[TimelinePicks.userId] })
            .filterValues { it.toSet().size >= 2 }.keys
        if (bothPicked.isNotEmpty()) {
            Files.selectAll().where { Files.id inList bothPicked }.forEach {
                val meta = it.toFileMeta()
                add(TimelineEntry(TimelineEntryKind.Photo, meta.id, meta.createdAt, meta.fileName, null, meta.uploadedBy, meta))
            }
        }
    }

    suspend fun picks(userId: UUID, roomId: UUID): List<TimelinePick> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        TimelinePicks.selectAll().where { TimelinePicks.roomId eq roomId }
            .map { TimelinePick(it[TimelinePicks.fileId], it[TimelinePicks.userId], it[TimelinePicks.createdAt]) }
    }

    /** 只能选这个房间里的图片；重复选中不报错。 */
    suspend fun pick(userId: UUID, roomId: UUID, fileId: UUID) = db.tx {
        rooms.requireMember(roomId, userId)
        val ok = Files.select(Files.id).where { (Files.id eq fileId) and (Files.roomId eq roomId) and (Files.kind eq FileKind.Image.wireName) }.any()
        if (!ok) notFound()
        TimelinePicks.insertIgnore {
            it[TimelinePicks.roomId] = roomId
            it[TimelinePicks.fileId] = fileId
            it[TimelinePicks.userId] = userId
            it[createdAt] = clock.instant()
        }
    }

    suspend fun unpick(userId: UUID, roomId: UUID, fileId: UUID) = db.tx {
        rooms.requireMember(roomId, userId)
        TimelinePicks.deleteWhere { (TimelinePicks.fileId eq fileId) and (TimelinePicks.userId eq userId) and (TimelinePicks.roomId eq roomId) }
    }
}
