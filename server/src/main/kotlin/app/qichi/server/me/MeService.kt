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
) {
    suspend fun get(userId: UUID): Me = db.tx { load(userId) }

    /** 改显示名、头像、通知偏好。显示名或头像变化时，所在的每个房间都产生一条 member 变化。 */
    suspend fun update(userId: UUID, req: UpdateMeRequest): Me {
        validate {
            req.displayName.ifPresent {
                check(it.trim().length in Limits.DISPLAY_NAME_LENGTH, "displayName", "显示名 1–32 个字")
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
        )
        val rooms = RoomMembers.join(Rooms, JoinType.INNER, RoomMembers.roomId, Rooms.id)
            .selectAll()
            .where { (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
            .orderBy(RoomMembers.joinedAt)
            .map { MyRoom(it[Rooms.id], it[Rooms.name], fromWire<MemberRole>(it[RoomMembers.role])) }
        return Me(user, rooms)
    }
}
