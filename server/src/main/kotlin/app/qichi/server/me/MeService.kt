package app.qichi.server.me

import app.qichi.server.db.Files
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RoomMembers
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Rooms
import app.qichi.server.db.Users
import app.qichi.server.db.tx
import app.qichi.server.plugins.validate
import app.qichi.shared.api.Me
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.api.MyRoom
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.api.User
import app.qichi.shared.api.ifPresent
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.fromWire
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.util.UUID

class MeService(
    private val db: QichiDatabase,
    private val writer: RoomWriter,
    private val clock: Clock,
    /** 服务端是否配置了 AI（告诉 App 要不要显示 AI 按钮） */
    private val aiEnabled: Boolean = false,
) {
    suspend fun get(userId: UUID): Me = db.tx { load(userId) }

    /**
     * 改显示名、头像、通知偏好、AI 能看什么、阅读的常用提示词（整套替换）。
     * 显示名或头像变化时，所在的每个房间都产生一条 member 变化；其余只是自己的设置，不进房间的同步。
     */
    suspend fun update(userId: UUID, req: UpdateMeRequest): Me {
        validate {
            req.displayName.ifPresent {
                check(it.trim().length in Limits.DISPLAY_NAME_LENGTH, "displayName", "显示名 1–32 个字")
            }
            req.readingPrompts.ifPresent { prompts ->
                check(prompts.size <= Limits.READING_PROMPTS_MAX, "readingPrompts", "常用提示词最多 ${Limits.READING_PROMPTS_MAX} 条")
                check(prompts.map { it.id }.toSet().size == prompts.size, "readingPrompts", "提示词的 id 重复了")
                check(prompts.all { it.title.trim().length in Limits.READING_PROMPT_TITLE_LENGTH }, "readingPrompts", "名字 1–20 个字")
                check(prompts.all { it.instruction.trim().length in Limits.READING_PROMPT_INSTRUCTION_LENGTH }, "readingPrompts", "要求 1–300 个字")
            }
            // 免打扰用的手机时区（P16-10）：要是认得的时区名
            req.notificationPrefs.ifPresent { json ->
                val zone = NotificationPrefs.from(json).timezone
                check(zone == null || runCatching { java.time.ZoneId.of(zone) }.isSuccess, "notificationPrefs", "时区不对")
            }
        }
        return db.tx {
            val memberships = RoomMembers.selectAll()
                .where { (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
                .map { it[RoomMembers.id] to it[RoomMembers.roomId] }
            req.avatarFileId.ifPresent { fileId ->
                if (fileId != null) {
                    val ok = Files.select(Files.id)
                        .where { (Files.id eq fileId) and (Files.roomId inList memberships.map { it.second }) }
                        .any()
                    validate { check(ok, "avatarFileId", "文件不存在") }
                }
            }
            val now = clock.instant()
            Users.update({ Users.id eq userId }) { row ->
                req.displayName.ifPresent { row[displayName] = it.trim() }
                req.avatarFileId.ifPresent { row[avatarFileId] = it }
                req.notificationPrefs.ifPresent { row[notificationPrefs] = it }
                req.aiPrefs.ifPresent { row[aiPrefs] = it }
                req.readingPrompts.ifPresent { list -> row[readingPrompts] = list.map { it.copy(title = it.title.trim(), instruction = it.instruction.trim()) } }
                row[updatedAt] = now
            }
            if (req.displayName.isPresent || req.avatarFileId.isPresent) {
                for ((memberId, roomId) in memberships) {
                    val seq = writer.change(this, roomId, EntityType.Member, memberId, userId, now)
                    RoomMembers.update({ RoomMembers.id eq memberId }) {
                        it[RoomMembers.seq] = seq
                        it[updatedAt] = now
                    }
                }
            }
            load(userId)
        }
    }

    private fun load(userId: UUID): Me {
        val row = Users.selectAll().where { Users.id eq userId }.single()
        val user = User(
            id = row[Users.id],
            username = row[Users.username],
            displayName = row[Users.displayName],
            avatarFileId = row[Users.avatarFileId],
            notificationPrefs = row[Users.notificationPrefs],
            createdAt = row[Users.createdAt],
            aiPrefs = row[Users.aiPrefs],
            readingPrompts = row[Users.readingPrompts],
        )
        val rooms = RoomMembers.join(Rooms, JoinType.INNER, RoomMembers.roomId, Rooms.id)
            .selectAll()
            .where { (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
            .orderBy(RoomMembers.joinedAt)
            .map { MyRoom(it[Rooms.id], it[Rooms.name], fromWire<MemberRole>(it[RoomMembers.role])) }
        return Me(user, rooms, aiEnabled)
    }
}
