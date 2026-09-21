package app.qichi.server.rooms

import app.qichi.server.db.RoomMembers
import app.qichi.server.db.Rooms
import app.qichi.server.db.Users
import app.qichi.shared.api.Member
import app.qichi.shared.api.Room
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** 房间与成员的查询（在事务里调用）。 */
object RoomRepository {

    fun room(roomId: UUID): Room? =
        Rooms.selectAll().where { Rooms.id eq roomId }.singleOrNull()?.toRoom()

    /** 锁住房间行（成员数校验、邀请等需要串行的操作）。 */
    fun lockRoom(roomId: UUID): Room? =
        Rooms.selectAll().where { Rooms.id eq roomId }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()?.toRoom()

    fun lastSeq(roomId: UUID): Long =
        Rooms.select(Rooms.lastSeq).where { Rooms.id eq roomId }.single()[Rooms.lastSeq]

    private fun memberQuery() = RoomMembers.join(Users, JoinType.INNER, RoomMembers.userId, Users.id).selectAll()

    /** 有效成员（未被移除）。 */
    fun activeMembers(roomId: UUID): List<Member> =
        memberQuery()
            .where { (RoomMembers.roomId eq roomId) and RoomMembers.deletedAt.isNull() }
            .orderBy(RoomMembers.joinedAt)
            .map { it.toMember() }

    /** 包括已移除的成员（bootstrap 用）。 */
    fun allMembers(roomId: UUID): List<Member> =
        memberQuery().where { RoomMembers.roomId eq roomId }.orderBy(RoomMembers.joinedAt).map { it.toMember() }

    fun member(memberId: UUID): Member? =
        memberQuery().where { RoomMembers.id eq memberId }.singleOrNull()?.toMember()

    fun activeMembership(roomId: UUID, userId: UUID): ResultRow? =
        RoomMembers.selectAll()
            .where { (RoomMembers.roomId eq roomId) and (RoomMembers.userId eq userId) and RoomMembers.deletedAt.isNull() }
            .singleOrNull()

    fun isMember(roomId: UUID, userId: UUID): Boolean = activeMembership(roomId, userId) != null

    fun ResultRow.toRoom() = Room(
        id = this[Rooms.id],
        name = this[Rooms.name],
        avatarFileId = this[Rooms.avatarFileId],
        heroFileId = this[Rooms.heroFileId],
        anniversary = this[Rooms.anniversary],
        timezone = this[Rooms.timezone],
        createdBy = this[Rooms.createdBy],
        seq = this[Rooms.seq],
        createdAt = this[Rooms.createdAt],
        updatedAt = this[Rooms.updatedAt],
    )

    fun ResultRow.toMember() = Member(
        id = this[RoomMembers.id],
        roomId = this[RoomMembers.roomId],
        seq = this[RoomMembers.seq],
        createdAt = this[RoomMembers.createdAt],
        updatedAt = this[RoomMembers.updatedAt],
        deletedAt = this[RoomMembers.deletedAt],
        deletedBy = this[RoomMembers.deletedBy],
        userId = this[RoomMembers.userId],
        role = fromWire<MemberRole>(this[RoomMembers.role]),
        username = this[Users.username],
        displayName = this[Users.displayName],
        avatarFileId = this[Users.avatarFileId],
        joinedAt = this[RoomMembers.joinedAt],
    )
}
