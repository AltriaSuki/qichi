package app.qichi.server.push

import app.qichi.server.db.BoardPosts
import app.qichi.server.db.CommittedChange
import app.qichi.server.db.ChangeNotifier
import app.qichi.server.db.Devices
import app.qichi.server.db.Events
import app.qichi.server.db.Messages
import app.qichi.server.db.MoodResponses
import app.qichi.server.db.Moods
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Rooms
import app.qichi.server.db.Todos
import app.qichi.server.db.Users
import app.qichi.server.db.tx
import app.qichi.server.rooms.RoomRepository
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PushProvider
import app.qichi.shared.model.wireName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** 推送的内容：只有「谁做了什么」和点开后去哪里（深链），不含正文。 */
@Serializable
data class PushPayload(
    val title: String,
    val body: String,
    /** qichi://room/{roomId}/{page}[/{id}] */
    val link: String,
    /** 同一个 tag 的通知在手机上合并成一条（比如同一个房间的新消息） */
    val tag: String,
)

/** 要推给谁、推什么、属于哪一类（用来查通知偏好）。 */
data class PushIntent(val recipients: Set<UUID>, val category: Category, val payload: PushPayload) {
    enum class Category { Messages, Moods, Qna, Todos, Events, Board }
}

private val log = LoggerFactory.getLogger(PushService::class.java)

/**
 * 后台推送（P3-10）：房间里有值得告诉对方的变化时，推给对方的设备。
 * 只推对方做的事；按对方的通知偏好（各类开关、免打扰时段，房间时区）过滤；
 * 手机在前台时由 App 自己忽略（那时 WebSocket 已经实时同步了）。
 */
class PushService(
    private val db: QichiDatabase,
    private val sender: PushSender?,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ChangeNotifier {
    private val json = Json { encodeDefaults = true }

    override suspend fun roomChanged(roomId: UUID, seq: Long) = Unit

    override suspend fun entityChanged(change: CommittedChange) {
        if (sender == null || change.op != ChangeOp.Upsert) return
        scope.launch {
            try {
                deliver(change)
            } catch (e: Exception) {
                log.warn("推送处理出错：{}", e.javaClass.simpleName, e)
            }
        }
    }

    /** 决定并发送（测试里直接调用，免得等后台协程）。 */
    suspend fun deliver(change: CommittedChange) {
        val intent = db.tx(readOnly = true) { decide(change) } ?: return
        val targets = db.tx(readOnly = true) {
            val zone = Rooms.select(Rooms.timezone).where { Rooms.id eq change.roomId }.singleOrNull()?.get(Rooms.timezone)
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Asia/Shanghai")
            val now = clock.instant().atZone(zone).toLocalTime()
            val wanted = Users.select(Users.id, Users.notificationPrefs).where { Users.id inList intent.recipients }
                .map { it[Users.id] to NotificationPrefs.from(it[Users.notificationPrefs]) }
                .filter { (_, prefs) -> prefs.allows(intent.category) && !prefs.isQuiet(now) }
                .map { it.first }
            if (wanted.isEmpty()) emptyList() else Devices.select(Devices.id, Devices.token)
                .where { (Devices.userId inList wanted) and (Devices.provider eq PushProvider.UnifiedPush.wireName) }
                .map { it[Devices.id] to it[Devices.token] }
        }
        val payload = json.encodeToString(PushPayload.serializer(), intent.payload)
        for ((deviceId, endpoint) in targets) {
            if (sender!!.send(endpoint, payload) == SendResult.Gone) {
                db.tx { Devices.deleteWhere { Devices.id eq deviceId } }
            }
        }
    }

    private fun NotificationPrefs.allows(c: PushIntent.Category) = when (c) {
        PushIntent.Category.Messages -> messages
        PushIntent.Category.Moods -> moods
        PushIntent.Category.Qna -> qna
        PushIntent.Category.Todos -> todos
        PushIntent.Category.Events -> events
        PushIntent.Category.Board -> board
    }

    /** 免打扰时段 [quietStart, quietEnd)，可以跨午夜。 */
    private fun NotificationPrefs.isQuiet(now: LocalTime): Boolean {
        if (!quietEnabled) return false
        val start = runCatching { LocalTime.parse(quietStart) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(quietEnd) }.getOrNull() ?: return false
        return if (start <= end) now >= start && now < end else now >= start || now < end
    }

    /** 这次变化要不要推、推给谁、说什么。只看新建的（改动、删除不推），除了问答揭晓。 */
    private fun decide(c: CommittedChange): PushIntent? {
        val actor = c.actorId
        val members = RoomRepository.activeMembers(c.roomId)
        val names = members.associate { it.userId to it.displayName }
        val others = members.map { it.userId }.filter { it != actor }.toSet()
        val who = actor?.let(names::get) ?: "对方"
        val room = c.roomId
        fun intent(category: PushIntent.Category, body: String, page: String, id: UUID? = null, to: Set<UUID> = others, tag: String = page) =
            if (to.isEmpty()) null else PushIntent(to, category, PushPayload("栖迟", body, "qichi://room/$room/$page" + (id?.let { "/$it" } ?: ""), "$room:$tag"))
        return when (c.type) {
            EntityType.Message -> {
                val row = Messages.selectAll().where { Messages.id eq c.entityId }.singleOrNull() ?: return null
                // 只推新发的、有人写的消息（撤回、删除、恢复都会产生变化，但 seq 不再等于 createdSeq）
                if (row[Messages.createdSeq] != c.seq || row[Messages.authorId] == null) return null
                val body = when (row[Messages.kind]) {
                    MessageKind.Image.wireName -> "${who}发来一张照片"
                    MessageKind.File.wireName -> "${who}发来一个文件"
                    MessageKind.Text.wireName -> "${who}发来一条消息"
                    else -> return null
                }
                intent(PushIntent.Category.Messages, body, "chat", c.entityId, to = others - row[Messages.authorId]!!)
            }
            EntityType.Mood -> {
                val row = Moods.selectAll().where { Moods.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Moods.createdAt] != row[Moods.updatedAt] || row[Moods.deletedAt] != null) return null
                intent(PushIntent.Category.Moods, if (row[Moods.needsComfort]) "${who}需要一点安慰" else "${who}记下了心情", "mood")
            }
            EntityType.MoodResponse -> {
                val row = MoodResponses.selectAll().where { MoodResponses.id eq c.entityId }.singleOrNull() ?: return null
                if (row[MoodResponses.createdAt] != row[MoodResponses.updatedAt] || row[MoodResponses.deletedAt] != null) return null
                val author = Moods.select(Moods.authorId).where { Moods.id eq row[MoodResponses.moodId] }.singleOrNull()?.get(Moods.authorId) ?: return null
                intent(PushIntent.Category.Moods, "${who}回应了你的心情", "mood", to = setOf(author) - setOfNotNull(actor))
            }
            EntityType.QnaRound -> {
                val row = QnaRounds.selectAll().where { QnaRounds.id eq c.entityId }.singleOrNull() ?: return null
                val revealed = row[QnaRounds.revealedAt] ?: return null
                // 刚揭晓的那一次（揭晓时刻就是这次更新的时刻）
                if (revealed != row[QnaRounds.updatedAt]) return null
                intent(PushIntent.Category.Qna, "今天的问答揭晓了", "qna")
            }
            EntityType.Todo -> {
                val row = Todos.selectAll().where { Todos.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Todos.createdAt] != row[Todos.updatedAt] || row[Todos.deletedAt] != null) return null
                val assignee = row[Todos.assigneeId] ?: return null
                if (assignee == actor || row[Todos.recurrencePrevId] != null) return null
                intent(PushIntent.Category.Todos, "${who}给你加了一件待办", "todo", to = setOf(assignee))
            }
            EntityType.Event -> {
                val row = Events.selectAll().where { Events.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Events.createdAt] != row[Events.updatedAt] || row[Events.deletedAt] != null || row[Events.icsUid] != null) return null
                intent(PushIntent.Category.Events, "${who}加了一个日程", "calendar")
            }
            EntityType.BoardPost -> {
                val row = BoardPosts.selectAll().where { BoardPosts.id eq c.entityId }.singleOrNull() ?: return null
                if (row[BoardPosts.createdAt] != row[BoardPosts.updatedAt] || row[BoardPosts.deletedAt] != null) return null
                intent(PushIntent.Category.Board, "${who}写了一条留言", "board", row[BoardPosts.topicId], tag = "board")
            }
            else -> null
        }
    }
}
