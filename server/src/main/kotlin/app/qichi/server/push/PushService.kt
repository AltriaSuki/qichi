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
import app.qichi.shared.api.PushPayload

/**
 * 要推给谁、推什么、属于哪一类（用来查通知偏好）。[payload] 带着内容（像 QQ 那样）；
 * 对方关掉「通知里显示内容」时改发 [plain]（只有「谁做了什么」）。
 */
data class PushIntent(val recipients: Set<UUID>, val category: Category, val payload: PushPayload, val plain: PushPayload = payload) {
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
                .associate { (id, prefs) -> id to prefs.showPreview }
            if (wanted.isEmpty()) emptyList() else Devices.select(Devices.id, Devices.token, Devices.userId)
                .where { (Devices.userId inList wanted.keys) and (Devices.provider eq PushProvider.UnifiedPush.wireName) }
                .map { Triple(it[Devices.id], it[Devices.token], wanted[it[Devices.userId]] == true) }
        }
        val full = json.encodeToString(PushPayload.serializer(), intent.payload)
        val plain = json.encodeToString(PushPayload.serializer(), intent.plain)
        for ((deviceId, endpoint, preview) in targets) {
            val payload = if (preview) full else plain
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
        val roomRow = Rooms.select(Rooms.name, Rooms.createdBy).where { Rooms.id eq room }.singleOrNull()
        val roomName = roomRow?.get(Rooms.name) ?: "栖迟"

        /**
         * [detail] 是带内容的正文（像 QQ：标题是谁，正文是说了什么）；[plainBody] 是只说「谁做了什么」的版本。
         * [title] 为空时用做这件事的人的名字。
         */
        fun intent(
            category: PushIntent.Category, detail: String, plainBody: String, page: String, id: UUID? = null,
            to: Set<UUID> = others, tag: String = page, kind: String = PushPayload.KIND_EVENT, title: String? = null, messageId: UUID? = null,
        ): PushIntent? {
            if (to.isEmpty()) return null
            val link = "qichi://room/$room/$page" + (id?.let { "/$it" } ?: "")
            val full = PushPayload(
                title = title ?: who, body = preview(detail), link = link, tag = "$room:$tag", kind = kind,
                sender = actor?.let { who }, senderId = actor, senderIsCreator = actor != null && actor == roomRow?.get(Rooms.createdBy),
                roomId = room, roomName = roomName, messageId = messageId, sentAt = clock.instant(),
            )
            val plain = PushPayload(title = roomName, body = plainBody, link = link, tag = "$room:$tag", roomId = room, roomName = roomName)
            return PushIntent(to, category, full, plain)
        }
        return when (c.type) {
            EntityType.Message -> {
                val row = Messages.selectAll().where { Messages.id eq c.entityId }.singleOrNull() ?: return null
                // 只推新发的、有人写的消息（撤回、删除、恢复都会产生变化，但 seq 不再等于 createdSeq）
                if (row[Messages.createdSeq] != c.seq || row[Messages.authorId] == null) return null
                val fileName = row[Messages.fileId]?.let { f ->
                    app.qichi.server.db.Files.select(app.qichi.server.db.Files.fileName).where { app.qichi.server.db.Files.id eq f }.singleOrNull()?.get(app.qichi.server.db.Files.fileName)
                }
                val (detail, plain) = when (row[Messages.kind]) {
                    MessageKind.Image.wireName -> listOf("[图片]", row[Messages.body]).filter { it.isNotBlank() }.joinToString(" ") to "${who}发来一张照片"
                    MessageKind.File.wireName -> "[文件] ${fileName.orEmpty()}".trim() to "${who}发来一个文件"
                    MessageKind.Text.wireName -> row[Messages.body] to "${who}发来一条消息"
                    else -> return null
                }
                intent(PushIntent.Category.Messages, detail, plain, "chat", c.entityId, to = others - row[Messages.authorId]!!,
                    kind = PushPayload.KIND_MESSAGE, messageId = c.entityId)
            }
            EntityType.Mood -> {
                val row = Moods.selectAll().where { Moods.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Moods.createdAt] != row[Moods.updatedAt] || row[Moods.deletedAt] != null) return null
                val what = if (row[Moods.needsComfort]) "需要一点安慰" else "记下了心情"
                intent(PushIntent.Category.Moods, listOfNotNull(what, row[Moods.note]?.takeIf { it.isNotBlank() }).joinToString("：") , "$who$what", "mood")
            }
            EntityType.MoodResponse -> {
                val row = MoodResponses.selectAll().where { MoodResponses.id eq c.entityId }.singleOrNull() ?: return null
                if (row[MoodResponses.createdAt] != row[MoodResponses.updatedAt] || row[MoodResponses.deletedAt] != null) return null
                val author = Moods.select(Moods.authorId).where { Moods.id eq row[MoodResponses.moodId] }.singleOrNull()?.get(Moods.authorId) ?: return null
                val reply = when (row[MoodResponses.kind]) {
                    "here" -> "我在这里"
                    "hug" -> "给你一个拥抱"
                    "ready" -> "等你准备好"
                    else -> "回应了你的心情"
                }
                intent(PushIntent.Category.Moods, "回应了你的心情：$reply", "${who}回应了你的心情", "mood", to = setOf(author) - setOfNotNull(actor))
            }
            EntityType.QnaRound -> {
                val row = QnaRounds.selectAll().where { QnaRounds.id eq c.entityId }.singleOrNull() ?: return null
                val revealed = row[QnaRounds.revealedAt] ?: return null
                // 刚揭晓的那一次（揭晓时刻就是这次更新的时刻）
                if (revealed != row[QnaRounds.updatedAt]) return null
                intent(PushIntent.Category.Qna, "今天的问答揭晓了，去看看彼此的回答", "今天的问答揭晓了", "qna", title = roomName)
            }
            EntityType.Todo -> {
                val row = Todos.selectAll().where { Todos.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Todos.createdAt] != row[Todos.updatedAt] || row[Todos.deletedAt] != null) return null
                val assignee = row[Todos.assigneeId] ?: return null
                if (assignee == actor || row[Todos.recurrencePrevId] != null) return null
                intent(PushIntent.Category.Todos, "给你加了一件待办：${row[Todos.title]}", "${who}给你加了一件待办", "todo", to = setOf(assignee))
            }
            EntityType.Event -> {
                val row = Events.selectAll().where { Events.id eq c.entityId }.singleOrNull() ?: return null
                if (row[Events.createdAt] != row[Events.updatedAt] || row[Events.deletedAt] != null || row[Events.icsUid] != null) return null
                intent(PushIntent.Category.Events, "加了一个日程：${row[Events.title]}", "${who}加了一个日程", "calendar")
            }
            EntityType.BoardPost -> {
                val row = BoardPosts.selectAll().where { BoardPosts.id eq c.entityId }.singleOrNull() ?: return null
                if (row[BoardPosts.createdAt] != row[BoardPosts.updatedAt] || row[BoardPosts.deletedAt] != null) return null
                intent(PushIntent.Category.Board, "写了一条留言：${row[BoardPosts.body]}", "${who}写了一条留言", "board", row[BoardPosts.topicId], tag = "board")
            }
            else -> null
        }
    }

    /** 推送里的内容：最多 [PushPayload.PREVIEW_MAX] 字，超出加省略号。 */
    private fun preview(text: String): String {
        val t = text.trim()
        return if (t.length <= PushPayload.PREVIEW_MAX) t else t.take(PushPayload.PREVIEW_MAX - 1) + "…"
    }
}
