package app.qichi.server.rooms

import app.qichi.server.db.Files
import app.qichi.server.db.Invites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RoomMembers
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Rooms
import app.qichi.server.db.Tx
import app.qichi.server.files.FileService
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository.toRoom
import app.qichi.shared.api.CreateRoomRequest
import app.qichi.shared.api.Invite
import app.qichi.shared.api.Room
import app.qichi.shared.api.RoomDetail
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.api.ifPresent
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MemberRole
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class RoomService(
    private val db: QichiDatabase,
    private val writer: RoomWriter,
    private val clock: Clock,
    private val files: FileService,
) {
    private val random = SecureRandom()

    /** 不是有效成员一律 404（不暴露房间是否存在）。 */
    fun requireMember(roomId: UUID, userId: UUID) {
        if (!RoomRepository.isMember(roomId, userId)) notFound()
    }

    suspend fun requireMemberTx(roomId: UUID, userId: UUID) = db.tx { requireMember(roomId, userId) }

    /** 建房间：创建者为 owner。同 id 已存在且属于自己时返回已有房间（created = false）。 */
    suspend fun create(userId: UUID, req: CreateRoomRequest): Pair<RoomDetail, Boolean> {
        val name = req.name.trim()
        val timezone = req.timezone?.trim() ?: Limits.DEFAULT_TIMEZONE
        validate {
            check(name.length in Limits.ROOM_NAME_LENGTH, "name", "房间名 1–40 个字")
            check(isValidZone(timezone), "timezone", "不是合法的时区名")
        }
        return db.tx {
            val existing = RoomRepository.room(req.id)
            if (existing != null) {
                if (!RoomRepository.isMember(req.id, userId)) conflictId()
                return@tx detail(req.id) to false
            }
            val now = clock.instant()
            val inserted = Rooms.insertIgnore {
                it[id] = req.id
                it[Rooms.name] = name
                it[Rooms.timezone] = timezone
                it[anniversary] = req.anniversary
                it[createdBy] = userId
                it[lastSeq] = 0
                it[seq] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }.insertedCount
            if (inserted == 0) conflictId()

            val roomSeq = writer.change(this, req.id, EntityType.Room, req.id, userId, now)
            Rooms.update({ Rooms.id eq req.id }) { it[seq] = roomSeq }
            addMember(this, req.id, userId, MemberRole.Owner)
            detail(req.id) to true
        }
    }

    suspend fun get(userId: UUID, roomId: UUID): RoomDetail = db.tx {
        requireMember(roomId, userId)
        detail(roomId)
    }

    suspend fun update(userId: UUID, roomId: UUID, req: UpdateRoomRequest): Room {
        validate {
            req.name.ifPresent { check(it.trim().length in Limits.ROOM_NAME_LENGTH, "name", "房间名 1–40 个字") }
            req.timezone.ifPresent { check(isValidZone(it.trim()), "timezone", "不是合法的时区名") }
        }
        val released = mutableListOf<String>()
        val room = db.tx {
            requireMember(roomId, userId)
            req.avatarFileId.ifPresent { id -> if (id != null) requireImageInRoom(id, roomId, "avatarFileId", FileKind.Avatar) }
            req.heroFileId.ifPresent { id -> if (id != null) requireImageInRoom(id, roomId, "heroFileId", FileKind.Hero) }
            val previousHero = Rooms.select(Rooms.heroFileId).where { Rooms.id eq roomId }.single()[Rooms.heroFileId]
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Room, roomId, userId, now)
            Rooms.update({ Rooms.id eq roomId }) { row ->
                req.name.ifPresent { row[name] = it.trim() }
                req.avatarFileId.ifPresent { row[avatarFileId] = it }
                req.heroFileId.ifPresent { row[heroFileId] = it }
                req.anniversary.ifPresent { row[anniversary] = it }
                req.timezone.ifPresent { row[timezone] = it.trim() }
                row[Rooms.seq] = seq
                row[updatedAt] = now
            }
            // 换下来的主视觉照片（专门为主视觉上传的那种）不再有用，连文件一起删掉
            req.heroFileId.ifPresent { newHero ->
                if (previousHero != null && previousHero != newHero) {
                    Files.select(Files.storagePath)
                        .where { (Files.id eq previousHero) and (Files.kind eq FileKind.Hero.wireName) }
                        .singleOrNull()
                        ?.let { released += it[Files.storagePath] }
                        ?.also { Files.deleteWhere { Files.id eq previousHero } }
                }
            }
            RoomRepository.room(roomId)!!
        }
        files.deleteStored(released)
        return room
    }

    /** 生成邀请码：仅 owner；房间满员时 409；之前未使用的邀请码全部失效。 */
    suspend fun createInvite(userId: UUID, roomId: UUID): Invite = db.tx {
        val membership = RoomRepository.activeMembership(roomId, userId) ?: notFound()
        if (membership[RoomMembers.role] != MemberRole.Owner.wireName) forbidden("只有房间的创建者可以邀请")
        RoomRepository.lockRoom(roomId)
        if (RoomRepository.activeMembers(roomId).size >= Limits.MAX_ROOM_MEMBERS) roomFull()

        val now = clock.instant()
        Invites.update({ (Invites.roomId eq roomId) and Invites.usedBy.isNull() and (Invites.expiresAt greater now) }) {
            it[expiresAt] = now
        }
        val expiresAt = now.plus(Duration.ofDays(Limits.INVITE_VALID_DAYS))
        var code: String
        do {
            code = newInviteCode()
            val inserted = Invites.insertIgnore {
                it[id] = UuidV7.generate()
                it[Invites.roomId] = roomId
                it[Invites.code] = code
                it[createdBy] = userId
                it[Invites.expiresAt] = expiresAt
                it[createdAt] = now
            }.insertedCount
        } while (inserted == 0)
        Invite(code = code, roomId = roomId, expiresAt = expiresAt, createdAt = now)
    }

    /** 已登录用户用邀请码加入房间。 */
    suspend fun acceptInvite(userId: UUID, code: String): RoomDetail = db.tx {
        val roomId = redeem(this, code.trim().uppercase(), userId)
        detail(roomId)
    }

    /**
     * 兑换邀请码（注册时也用它）：校验邀请码 → 已是成员直接返回 → 锁房间校验人数 → 加入 → 标记已使用。
     * @return 房间 id
     */
    fun redeem(tx: Tx, code: String, userId: UUID): UUID {
        val now = clock.instant()
        val invite = Invites.selectAll().where { Invites.code eq code }
            .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
            ?: inviteInvalid()
        val roomId = invite[Invites.roomId]
        if (RoomRepository.isMember(roomId, userId)) return roomId
        if (invite[Invites.usedBy] != null || !invite[Invites.expiresAt].isAfter(now)) inviteInvalid()

        RoomRepository.lockRoom(roomId) ?: inviteInvalid()
        if (RoomRepository.activeMembers(roomId).size >= Limits.MAX_ROOM_MEMBERS) roomFull()

        addMember(tx, roomId, userId, MemberRole.Member)
        Invites.update({ Invites.id eq invite[Invites.id] }) {
            it[usedBy] = userId
            it[usedAt] = now
        }
        return roomId
    }

    private fun addMember(tx: Tx, roomId: UUID, userId: UUID, role: MemberRole) {
        val now = clock.instant()
        val memberId = UuidV7.generate()
        val seq = writer.change(tx, roomId, EntityType.Member, memberId, userId, now)
        RoomMembers.insert {
            it[id] = memberId
            it[RoomMembers.roomId] = roomId
            it[RoomMembers.userId] = userId
            it[RoomMembers.role] = role.wireName
            it[joinedAt] = now
            it[RoomMembers.seq] = seq
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun detail(roomId: UUID) = RoomDetail(
        room = Rooms.selectAll().where { Rooms.id eq roomId }.single().toRoom(),
        members = RoomRepository.activeMembers(roomId),
        lastSeq = RoomRepository.lastSeq(roomId),
    )

    /** 头像、主视觉只能用这个房间里的图片（专门上传的 [kind]，或聊天里的图片）。 */
    private fun requireImageInRoom(fileId: UUID, roomId: UUID, field: String, kind: FileKind) {
        val fileKind = Files.select(Files.kind).where { (Files.id eq fileId) and (Files.roomId eq roomId) }.singleOrNull()?.get(Files.kind)
        validate {
            when (fileKind) {
                null -> fail(field, "文件不存在")
                kind.wireName, FileKind.Image.wireName -> Unit
                else -> fail(field, "需要一张图片")
            }
        }
    }

    private fun newInviteCode(): String =
        buildString { repeat(Limits.INVITE_LENGTH) { append(Limits.INVITE_ALPHABET[random.nextInt(Limits.INVITE_ALPHABET.length)]) } }

    companion object {
        fun isValidZone(zone: String): Boolean = zone in ZoneId.getAvailableZoneIds()

        fun inviteInvalid(): Nothing = throw ApiException(ProblemCode.InviteInvalid, "邀请码无效或已过期")
        fun roomFull(): Nothing = throw ApiException(ProblemCode.RoomFull, "房间已经有两个人了")
        fun conflictId(): Nothing = throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
    }
}
