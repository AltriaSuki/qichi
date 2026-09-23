package app.qichi.server.calendar

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Events
import app.qichi.server.db.Milestones
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Rooms
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.events.toEvent
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.API_PREFIX
import app.qichi.shared.api.CalendarImportResult
import app.qichi.shared.api.CalendarSubscription
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

class CalendarService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
    private val writer: RoomWriter,
    private val clock: Clock,
    private val publicBaseUrl: String,
) {
    /** 解析放在事务外，整批导入仍在一个事务内；重复 UID 不增加房间序号。 */
    suspend fun import(userId: UUID, roomId: UUID, bytes: ByteArray): CalendarImportResult {
        val zone = db.tx {
            rooms.requireMember(roomId, userId)
            ZoneId.of(Rooms.selectAll().where { Rooms.id eq roomId }.single()[Rooms.timezone])
        }
        val (parsed, invalid) = try {
            IcsCodec.parse(bytes, zone)
        } catch (_: Exception) {
            throw ApiException(ProblemCode.InvalidRequest, "ICS 文件格式不正确")
        }
        if (parsed.size > 1000) throw ApiException(ProblemCode.InvalidRequest, "ICS 文件中的日程太多")
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            var imported = 0
            var skipped = invalid
            val seen = mutableSetOf<String>()
            parsed.forEach { item ->
                if (!seen.add(item.uid) || Events.selectAll().where {
                        (Events.roomId eq roomId) and (Events.icsUid eq item.uid)
                    }.any()) {
                    skipped++
                } else {
                    val id = UuidV7.generate()
                    writes.create(this, roomId, userId, EntityType.Event, id, Events,
                        { key -> Events.selectAll().where { Events.id eq key }.singleOrNull()?.toEvent() }) {
                        it[Events.title] = item.title
                        it[Events.note] = item.note
                        it[Events.location] = item.location
                        it[Events.allDay] = item.allDay
                        it[Events.startsAt] = item.startsAt
                        it[Events.endsAt] = item.endsAt
                        it[Events.startDate] = item.startDate
                        it[Events.endDate] = item.endDate
                        it[Events.participantIds] = emptyList()
                        it[Events.createdBy] = userId
                        it[Events.icsUid] = item.uid
                    }
                    imported++
                }
            }
            CalendarImportResult(imported, skipped)
        }
    }

    suspend fun export(userId: UUID, roomId: UUID): ByteArray = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        IcsCodec.write(entries(roomId))
    }

    suspend fun subscribe(userId: UUID, roomId: UUID, reset: Boolean): CalendarSubscription = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        var token = Rooms.selectAll().where { Rooms.id eq roomId }.single()[Rooms.icsToken]
        if (token == null || reset) {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Room, roomId, userId, now)
            Rooms.update({ Rooms.id eq roomId }) {
                it[icsToken] = token
                it[Rooms.seq] = seq
                it[updatedAt] = now
            }
        }
        CalendarSubscription("${publicBaseUrl.trimEnd('/')}$API_PREFIX/ics/$token.ics")
    }

    suspend fun feed(token: String): ByteArray = db.tx(readOnly = true) {
        val roomId = Rooms.selectAll().where { Rooms.icsToken eq token }.singleOrNull()?.get(Rooms.id) ?: notFound()
        IcsCodec.write(entries(roomId))
    }

    private fun entries(roomId: UUID): List<IcsEntry> = buildList {
        Events.selectAll().where { (Events.roomId eq roomId) and Events.deletedAt.isNull() }.forEach { row ->
            val event = row.toEvent()
            add(IcsEntry(event.icsUid ?: "event-${event.id}@qichi", event.title, event.note, event.location,
                event.startDate, event.endDate, event.startsAt, event.endsAt))
        }
        // 已完成的待办、里程碑不再出现在订阅里（与 App 里的日历一致）
        Todos.selectAll().where { (Todos.roomId eq roomId) and Todos.deletedAt.isNull() and Todos.doneAt.isNull() }.forEach { row ->
            val date = row[Todos.dueDate]
            val instant = row[Todos.dueAt]
            if (date != null) add(IcsEntry("todo-${row[Todos.id]}@qichi", "待办 · ${row[Todos.title]}", startDate = date, endDate = date))
            else if (instant != null) add(IcsEntry("todo-${row[Todos.id]}@qichi", "待办 · ${row[Todos.title]}",
                startsAt = instant, endsAt = instant.plusSeconds(30 * 60)))
        }
        Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() and Plans.targetDate.isNotNull() }.forEach { row ->
            val date = row[Plans.targetDate]!!
            add(IcsEntry("plan-${row[Plans.id]}@qichi", "计划目标 · ${row[Plans.title]}", startDate = date, endDate = date))
        }
        Milestones.selectAll().where { (Milestones.roomId eq roomId) and Milestones.deletedAt.isNull() and Milestones.doneAt.isNull() and Milestones.targetDate.isNotNull() }.forEach { row ->
            val date = row[Milestones.targetDate]!!
            add(IcsEntry("milestone-${row[Milestones.id]}@qichi", "里程碑 · ${row[Milestones.title]}", startDate = date, endDate = date))
        }
    }
}
