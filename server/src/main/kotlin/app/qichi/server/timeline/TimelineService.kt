package app.qichi.server.timeline

import app.qichi.server.db.Answers
import app.qichi.server.db.Decisions
import app.qichi.server.db.DocumentVersions
import app.qichi.server.db.Documents
import app.qichi.server.db.Files
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Milestones
import app.qichi.server.db.Moods
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.server.db.Rooms
import app.qichi.server.db.TimelinePicks
import app.qichi.server.db.tx
import app.qichi.server.files.toFileMeta
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.DayPhoto
import app.qichi.shared.api.OnThisDay
import app.qichi.shared.api.TimelineAnswer
import app.qichi.shared.api.TimelineEntry
import app.qichi.shared.api.TimelineMonthCount
import app.qichi.shared.api.TimelinePage
import app.qichi.shared.api.TimelinePick
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.TimelineEntryKind
import app.qichi.shared.model.fromWireOrNull
import app.qichi.shared.model.wireName
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 共同时间线（P6-03，P10-09 改成按天的日记）：不单独存数据，按房间时区的月份把心情、揭晓了的问答、定下的决定、
 * 计划的进展和完成、灵感、文稿存的版本、两个人都选中的照片拼在一起；App 再按天分组。
 * 照片由两个人各自选中（timeline_picks），两人都选了才出现；没揭晓的问答不出现。
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
            val zone = zoneOf(roomId)
            val all = entries(roomId) + diaryEntries(roomId, zone)
            fun ym(at: Instant) = YearMonth.from(at.atZone(zone))
            val months = all.groupingBy { ym(it.at) }.eachCount()
                .entries.sortedByDescending { it.key }.map { TimelineMonthCount(it.key.year, it.key.monthValue, it.value) }
            val target = if (year != null) YearMonth.of(year, month!!) else months.firstOrNull()?.let { YearMonth.of(it.year, it.month) } ?: YearMonth.from(clock.instant().atZone(zone))
            TimelinePage(target.year, target.monthValue, all.filter { ym(it.at) == target }.sortedBy { it.at }, months)
        }
    }

    private fun zoneOf(roomId: UUID): ZoneId =
        Rooms.select(Rooms.timezone).where { Rooms.id eq roomId }.single()[Rooms.timezone].let { runCatching { ZoneId.of(it) }.getOrDefault(ZoneId.of("Asia/Shanghai")) }

    /** 心情、揭晓的问答、计划进展、文稿存档（P10-09）。 */
    private fun diaryEntries(roomId: UUID, zone: ZoneId): List<TimelineEntry> = buildList {
        Moods.selectAll().where { (Moods.roomId eq roomId) and Moods.deletedAt.isNull() }.forEach {
            add(
                TimelineEntry(
                    TimelineEntryKind.Mood, it[Moods.id], it[Moods.createdAt], it[Moods.label], it[Moods.note], it[Moods.authorId],
                    moodLabel = fromWireOrNull<MoodLabel>(it[Moods.label]), intensity = it[Moods.intensity].toInt(),
                ),
            )
        }
        // 问答：只有两个人都答完、揭晓了的才上时间线
        val rounds = QnaRounds.selectAll().where { (QnaRounds.roomId eq roomId) and QnaRounds.deletedAt.isNull() and QnaRounds.revealedAt.isNotNull() }.toList()
        if (rounds.isNotEmpty()) {
            val questions = Questions.select(Questions.id, Questions.text).where { Questions.id inList rounds.map { it[QnaRounds.questionId] } }
                .associate { it[Questions.id] to it[Questions.text] }
            val answers = Answers.selectAll().where { (Answers.roundId inList rounds.map { it[QnaRounds.id] }) and Answers.deletedAt.isNull() }
                .groupBy({ it[Answers.roundId] }, { TimelineAnswer(it[Answers.authorId], it[Answers.body]) })
            rounds.forEach { r ->
                add(
                    TimelineEntry(
                        TimelineEntryKind.Qna, r[QnaRounds.id], r[QnaRounds.revealedAt]!!, questions[r[QnaRounds.questionId]].orEmpty(),
                        answers = answers[r[QnaRounds.id]].orEmpty(),
                    ),
                )
            }
        }
        // 计划进展：阶段、里程碑做完
        val planTitles = Plans.select(Plans.id, Plans.title).where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.associate { it[Plans.id] to it[Plans.title] }
        PlanStages.selectAll().where { (PlanStages.roomId eq roomId) and PlanStages.deletedAt.isNull() and PlanStages.doneAt.isNotNull() }.forEach {
            val plan = planTitles[it[PlanStages.planId]] ?: return@forEach
            add(TimelineEntry(TimelineEntryKind.PlanProgress, it[PlanStages.planId], it[PlanStages.doneAt]!!, plan, "完成了「${it[PlanStages.title]}」"))
        }
        Milestones.selectAll().where { (Milestones.roomId eq roomId) and Milestones.deletedAt.isNull() and Milestones.doneAt.isNotNull() }.forEach {
            val plan = planTitles[it[Milestones.planId]] ?: return@forEach
            add(TimelineEntry(TimelineEntryKind.PlanProgress, it[Milestones.planId], it[Milestones.doneAt]!!, plan, "到了里程碑「${it[Milestones.title]}」"))
        }
        // 文稿：同一篇同一天只列最新一版
        val docs = Documents.select(Documents.id, Documents.title).where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.associate { it[Documents.id] to it[Documents.title] }
        if (docs.isNotEmpty()) {
            DocumentVersions.select(DocumentVersions.documentId, DocumentVersions.version, DocumentVersions.authorId, DocumentVersions.charCount, DocumentVersions.createdAt)
                .where { DocumentVersions.documentId inList docs.keys }
                .groupBy { it[DocumentVersions.documentId] to it[DocumentVersions.createdAt].atZone(zone).toLocalDate() }
                .values.forEach { sameDay ->
                    val last = sameDay.maxBy { it[DocumentVersions.version] }
                    add(
                        TimelineEntry(
                            TimelineEntryKind.Writing, last[DocumentVersions.documentId], last[DocumentVersions.createdAt],
                            docs[last[DocumentVersions.documentId]].orEmpty(), "${last[DocumentVersions.charCount]} 字", last[DocumentVersions.authorId],
                            version = last[DocumentVersions.version],
                        ),
                    )
                }
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
            // 照片下面的说明（P10-04）取发这张照片的那条消息
            val captions = Messages.select(Messages.fileId, Messages.body).where { (Messages.fileId inList bothPicked) and Messages.retractedAt.isNull() }
                .associate { it[Messages.fileId]!! to it[Messages.body] }
            Files.selectAll().where { Files.id inList bothPicked }.forEach {
                val meta = it.toFileMeta()
                add(TimelineEntry(TimelineEntryKind.Photo, meta.id, meta.createdAt, meta.fileName, captions[meta.id]?.ifBlank { null }, meta.uploadedBy, meta))
            }
        }
    }

    /** 某一天（房间时区）聊天里发过的照片，最多 12 张（「一年前的今天」）。 */
    suspend fun onThisDay(userId: UUID, roomId: UUID, date: LocalDate): OnThisDay = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        val zone = zoneOf(roomId)
        val from = date.atStartOfDay(zone).toInstant()
        val until = date.plusDays(1).atStartOfDay(zone).toInstant()
        val photos = Messages.join(Files, JoinType.INNER, Messages.fileId, Files.id).selectAll().where {
            (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() and
                (Messages.kind eq MessageKind.Image.wireName) and (Messages.createdAt greaterEq from) and (Messages.createdAt less until)
        }.orderBy(Messages.createdAt).limit(12).map { DayPhoto(it[Messages.id], it.toFileMeta(), it[Messages.authorId], it[Messages.createdAt]) }
        OnThisDay(date, photos)
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
