package app.qichi.server.trash

import app.qichi.server.db.EntityWrites
import app.qichi.server.db.Events
import app.qichi.server.db.Messages
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Questions
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Answers
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.SyncedTable
import app.qichi.server.db.Todos
import app.qichi.server.db.Tx
import app.qichi.server.db.tx
import app.qichi.server.events.toEvent
import app.qichi.server.files.FileService
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.moods.toMood
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.qna.toQuestion
import app.qichi.server.rooms.RoomService
import app.qichi.server.todos.TodoService
import app.qichi.server.todos.toTodo
import app.qichi.shared.api.Change
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.TrashItem
import app.qichi.shared.api.TrashPage
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.TrashType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 回收站（P3-03）：列表、恢复、彻底删除。第 3 阶段包括消息、心情、待办、日程。
 * 心情只有作者能删，所以也只有作者能恢复或彻底删除；其它类型两位成员都可以。
 */
class TrashService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writer: RoomWriter,
    private val writes: EntityWrites,
    private val todos: TodoService,
    private val files: FileService,
    private val clock: Clock,
) {
    /** 游标 = 上一页最后一条的 (deletedAt, id)。顺序：deletedAt 降序，同一时刻按 id 降序。 */
    private data class Cursor(val deletedAt: Instant, val id: UUID) {
        fun encode() = "${ChronoUnit.MICROS.between(Instant.EPOCH, deletedAt)}_$id"

        companion object {
            fun decode(text: String): Cursor? = runCatching {
                val (micros, id) = text.split('_', limit = 2)
                Cursor(Instant.EPOCH.plus(micros.toLong(), ChronoUnit.MICROS), UUID.fromString(id))
            }.getOrNull()
        }
    }

    private data class Candidate(val type: TrashType, val entity: SyncEntity, val deletedAt: Instant, val deletedBy: UUID) {
        // PostgreSQL 的 uuid 按字节无符号比较，与小写十六进制字符串的字典序一致
        val sortKey get() = id.toString()
        val id: UUID get() = entity.id
    }

    suspend fun list(userId: UUID, roomId: UUID, cursorText: String?, limit: Int): TrashPage {
        val cursor = cursorText?.let(Cursor::decode)
        validate {
            check(cursorText == null || cursor != null, "cursor", "不是有效的游标")
            check(limit in 1..100, "limit", "1–100")
        }
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val take = limit + 1
            val candidates = buildList {
                messageQuery().deleted(Messages, roomId, cursor, take).forEach { row ->
                    val m = row.toMessage()
                    add(Candidate(TrashType.Message, m, m.deletedAt!!, m.deletedBy!!))
                }
                Moods.selectAll().deleted(Moods, roomId, cursor, take).forEach { row ->
                    val m = row.toMood()
                    add(Candidate(TrashType.Mood, m, m.deletedAt!!, m.deletedBy!!))
                }
                // 随父待办一起删除的子任务不单独列出（父待办已在回收站里）
                val parent = Todos.alias("parent")
                Todos.join(parent, JoinType.LEFT, Todos.parentId, parent[Todos.id])
                    .select(Todos.columns)
                    .where { Todos.parentId.isNull() or parent[Todos.deletedAt].isNull() }
                    .deleted(Todos, roomId, cursor, take)
                    .forEach { row ->
                        val t = row.toTodo()
                        add(Candidate(TrashType.Todo, t, t.deletedAt!!, t.deletedBy!!))
                    }
                Events.selectAll().deleted(Events, roomId, cursor, take).forEach { row ->
                    val e = row.toEvent()
                    add(Candidate(TrashType.Event, e, e.deletedAt!!, e.deletedBy!!))
                }
                Questions.selectAll().deleted(Questions, roomId, cursor, take).forEach { row ->
                    val q = row.toQuestion()
                    add(Candidate(TrashType.Question, q, q.deletedAt!!, q.deletedBy!!))
                }
            }.sortedWith(compareByDescending<Candidate> { it.deletedAt }.thenByDescending { it.sortKey })

            val page = candidates.take(limit)
            TrashPage(
                items = page.map { TrashItem(it.type, it.id, it.deletedAt, it.deletedBy, EntityCodec.encode(it.type.entityType, it.entity)) },
                nextCursor = if (candidates.size > limit) page.last().let { Cursor(it.deletedAt, it.id).encode() } else null,
            )
        }
    }

    /** 恢复：清空 deletedAt / deletedBy，内容按删除前的状态回来（消息的 createdSeq 不变，回到原位置）。 */
    suspend fun restore(userId: UUID, roomId: UUID, type: TrashType, id: UUID): Change = db.tx {
        rooms.requireMember(roomId, userId)
        val table = type.table
        val row = trashed(table, roomId, id)
        checkOwner(type, id, userId)
        when (type) {
            TrashType.Todo -> {
                // 父待办也在回收站里时，子任务不能单独恢复（它不在列表里）
                if (row.parentDeleted) notFound()
                todos.restoreWithChildren(this, roomId, userId, id)
            }
            else -> writes.restore(this, roomId, userId, type.entityType, id, table)
        }
        val entity = load(type, id)!!
        Change(entity.seq, type.entityType, id, ChangeOp.Upsert, EntityCodec.encode(type.entityType, entity))
    }

    /** 彻底删除：只能删已在回收站里的；写 op = delete 的变化，客户端收到后物理删除本地行。 */
    suspend fun purge(userId: UUID, roomId: UUID, type: TrashType, id: UUID) {
        val orphanFiles = mutableListOf<String>()
        db.tx {
            rooms.requireMember(roomId, userId)
            val row = trashed(type.table, roomId, id)
            checkOwner(type, id, userId)
            if (type == TrashType.Todo && row.parentDeleted) notFound()
            val now = clock.instant()
            when (type) {
                TrashType.Message -> {
                    // 回复了它的消息：摘要清空（外键会把 reply_to_id 置空）
                    Messages.select(Messages.id).where { Messages.replyToId eq id }.map { it[Messages.id] }.forEach { reply ->
                        writes.update(this, roomId, userId, EntityType.Message, reply, Messages) {
                            it[Messages.replyExcerpt] = null
                            it[Messages.replyToId] = null
                        }
                    }
                    val fileId = Messages.select(Messages.fileId).where { Messages.id eq id }.single()[Messages.fileId]
                    hardDelete(this, roomId, userId, EntityType.Message, id, Messages, now)
                    fileId?.let { files.releaseIfUnused(it)?.let(orphanFiles::add) }
                }
                TrashType.Mood -> {
                    MoodResponses.select(MoodResponses.id).where { MoodResponses.moodId eq id }.map { it[MoodResponses.id] }
                        .forEach { hardDelete(this, roomId, userId, EntityType.MoodResponse, it, MoodResponses, now) }
                    hardDelete(this, roomId, userId, EntityType.Mood, id, Moods, now)
                }
                TrashType.Todo -> {
                    // 由它生成的下一次重复：来源置空
                    Todos.select(Todos.id).where { Todos.recurrencePrevId eq id }.map { it[Todos.id] }.forEach { next ->
                        writes.update(this, roomId, userId, EntityType.Todo, next, Todos) { it[Todos.recurrencePrevId] = null }
                    }
                    Todos.select(Todos.id).where { Todos.parentId eq id }.map { it[Todos.id] }
                        .forEach { hardDelete(this, roomId, userId, EntityType.Todo, it, Todos, now) }
                    hardDelete(this, roomId, userId, EntityType.Todo, id, Todos, now)
                }
                TrashType.Event -> hardDelete(this, roomId, userId, EntityType.Event, id, Events, now)
                TrashType.Question -> {
                    QnaRounds.selectAll().where { QnaRounds.questionId eq id }.map { it[QnaRounds.id] }.forEach { roundId ->
                        Answers.selectAll().where { Answers.roundId eq roundId }.map { it[Answers.id] }.forEach { answerId ->
                            hardDelete(this, roomId, userId, EntityType.Answer, answerId, Answers, now)
                        }
                        hardDelete(this, roomId, userId, EntityType.QnaRound, roundId, QnaRounds, now)
                    }
                    hardDelete(this, roomId, userId, EntityType.Question, id, Questions, now)
                }
            }
        }
        files.deleteStored(orphanFiles)
    }

    private class TrashedRow(val parentDeleted: Boolean)

    /** 必须是这个房间里、已在回收站里的实体，否则 404。 */
    private fun trashed(table: SyncedTable, roomId: UUID, id: UUID): TrashedRow {
        val row = table.selectAll().where { (table.id eq id) and (table.roomId eq roomId) and table.deletedAt.isNotNull() }
            .singleOrNull() ?: notFound()
        val parentDeleted = table == Todos && row[Todos.parentId]?.let { parentId ->
            Todos.select(Todos.deletedAt).where { Todos.id eq parentId }.singleOrNull()?.get(Todos.deletedAt) != null
        } == true
        return TrashedRow(parentDeleted)
    }

    private fun checkOwner(type: TrashType, id: UUID, userId: UUID) {
        if (type != TrashType.Mood) return
        val author = Moods.select(Moods.authorId).where { Moods.id eq id }.single()[Moods.authorId]
        if (author != userId) forbidden("只能处理自己的心情")
    }

    private fun hardDelete(tx: Tx, roomId: UUID, userId: UUID, type: EntityType, id: UUID, table: SyncedTable, at: Instant) {
        writer.change(tx, roomId, type, id, userId, at, ChangeOp.Delete)
        table.deleteWhere { table.id eq id }
    }

    private fun load(type: TrashType, id: UUID): SyncEntity? = when (type) {
        TrashType.Message -> messageQuery().where { Messages.id eq id }.singleOrNull()?.toMessage()
        TrashType.Mood -> Moods.selectAll().where { Moods.id eq id }.singleOrNull()?.toMood()
        TrashType.Todo -> Todos.selectAll().where { Todos.id eq id }.singleOrNull()?.toTodo()
        TrashType.Event -> Events.selectAll().where { Events.id eq id }.singleOrNull()?.toEvent()
        TrashType.Question -> Questions.selectAll().where { Questions.id eq id }.singleOrNull()?.toQuestion()
    }

    /** 在查询上加「这个房间、已删除、在游标之后」，按 (deletedAt, id) 降序取 [take] 条。 */
    private fun Query.deleted(table: SyncedTable, roomId: UUID, cursor: Cursor?, take: Int): Query {
        var cond: Op<Boolean> = (table.roomId eq roomId) and table.deletedAt.isNotNull()
        if (cursor != null) {
            cond = cond and ((table.deletedAt less cursor.deletedAt) or ((table.deletedAt eq cursor.deletedAt) and (table.id less cursor.id)))
        }
        return andWhere { cond }
            .orderBy(table.deletedAt to SortOrder.DESC, table.id to SortOrder.DESC)
            .limit(take)
    }
}

val TrashType.entityType: EntityType
    get() = when (this) {
        TrashType.Message -> EntityType.Message
        TrashType.Mood -> EntityType.Mood
        TrashType.Todo -> EntityType.Todo
        TrashType.Event -> EntityType.Event
        TrashType.Question -> EntityType.Question
    }

private val TrashType.table: SyncedTable
    get() = when (this) {
        TrashType.Message -> Messages
        TrashType.Mood -> Moods
        TrashType.Todo -> Todos
        TrashType.Event -> Events
        TrashType.Question -> Questions
    }
