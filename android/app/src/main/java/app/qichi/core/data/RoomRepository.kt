package app.qichi.core.data

import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.network.patch
import app.qichi.core.network.post
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AcceptInviteRequest
import app.qichi.shared.api.CreateRoomRequest
import app.qichi.shared.api.Invite
import app.qichi.shared.api.PasswordResetCode
import app.qichi.shared.api.AiUsage
import app.qichi.shared.api.Me
import app.qichi.shared.api.Member
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Room
import app.qichi.shared.api.RoomDetail
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import app.qichi.core.sync.offMain

/**
 * 「我」与房间：/me、建房间、邀请码（需要联网）；房间与成员从本机数据库读；改房间设置走发件箱（可离线）。
 */
class RoomRepository(
    private val api: ApiClient,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val syncEngine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val profile: ProfileStore,
) {
    val me: Flow<Me?> get() = profile.me
    val currentRoomId: Flow<UUID?> get() = profile.currentRoomId

    /** 「我发起的 AI 使用」（服务端统计，需要联网）。 */
    suspend fun aiUsage(month: YearMonth): AiUsage = api.get("me/ai-usage?month=$month")

    /** 从服务端刷新 /me 并保存；当前房间不在列表里时改选第一个。 */
    suspend fun refreshMe(): Me {
        val me = api.get<Me>("me")
        profile.saveMe(me)
        val current = profile.currentRoomId.first()
        if (current == null || me.rooms.none { it.roomId == current }) {
            profile.setCurrentRoom(me.rooms.firstOrNull()?.roomId)
        }
        return me
    }

    suspend fun createRoom(name: String, anniversary: LocalDate?): RoomDetail {
        val detail = api.post<RoomDetail>(
            "rooms",
            CreateRoomRequest(id = UuidV7.generate(), name = name.trim(), timezone = ZoneId.systemDefault().id, anniversary = anniversary),
        )
        enterRoom(detail.room.id)
        return detail
    }

    suspend fun acceptInvite(code: String): RoomDetail {
        val detail = api.post<RoomDetail>("invites/accept", AcceptInviteRequest(code.trim().uppercase()))
        enterRoom(detail.room.id)
        return detail
    }

    suspend fun createInvite(roomId: UUID): Invite = api.post("rooms/$roomId/invites")

    /** 给忘了密码的另一个人生成重置码（P16-04）：只在这次回应里出现，要在线。 */
    suspend fun createResetCode(roomId: UUID, userId: UUID): PasswordResetCode = api.post("rooms/$roomId/members/$userId/password-reset")

    /**
     * 退出房间（P16-07，需要联网）：房间和内容留给另一个人。成功后删掉本机这个房间的数据，
     * 换到还在的另一个房间（没有了就回到建房间 / 加入房间）。
     */
    suspend fun leave(roomId: UUID) {
        api.execute(io.ktor.http.HttpMethod.Post, "rooms/$roomId/leave")
        val id = roomId.toString()
        db.transaction {
            db.entities().deleteRoom(id)
            db.outbox().deleteRoom(id)
            db.syncState().deleteRoom(id)
            db.chatHistory().deleteRoom(id)
            db.drafts().deleteRoom(id)
        }
        refreshMe()
    }

    private suspend fun enterRoom(roomId: UUID) {
        profile.setCurrentRoom(roomId)
        refreshMe()
        syncEngine.pull(roomId)
    }

    fun observeRoom(roomId: UUID): Flow<Room?> =
        db.entities().observe(EntityType.Room.wireName, roomId.toString())
            .map { row -> row?.let { LocalStore.toLocal<Room>(it).value } }
            .offMain()

    /**
     * 房间成员，包括已经退出、注销的（deletedAt 不为空）：他们写过的内容还在，要显示名字（如「已注销的成员」，P16-07）。
     * 只要现在的成员时用 [People.partner] 或按 deletedAt 过滤。
     */
    fun observeMembers(roomId: UUID): Flow<List<Member>> =
        db.entities().observeAllByType(roomId.toString(), EntityType.Member.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Member>(it).value } }
            .offMain()

    /** 改房间设置：本机立即生效，经发件箱发出（离线也可以改）。 */
    suspend fun updateRoom(roomId: UUID, change: UpdateRoomRequest) {
        val current = store.get<Room>(EntityType.Room, roomId)?.value ?: return
        var updated = current
        (change.name as? Patch.Value)?.let { updated = updated.copy(name = it.value.trim()) }
        (change.anniversary as? Patch.Value)?.let { updated = updated.copy(anniversary = it.value) }
        (change.timezone as? Patch.Value)?.let { updated = updated.copy(timezone = it.value) }
        (change.avatarFileId as? Patch.Value)?.let { updated = updated.copy(avatarFileId = it.value) }
        (change.heroFileId as? Patch.Value)?.let { updated = updated.copy(heroFileId = it.value) }
        store.writeLocal(roomId, updated, OutboxOp.patch("rooms/$roomId", change))
        scheduler.kickOutbox()
    }

    /** 改显示名等个人资料（需要联网）；成功后刷新本机的 /me 与成员。 */
    suspend fun updateMe(change: UpdateMeRequest): Me {
        val me = api.patch<Me>("me", change)
        profile.saveMe(me)
        me.rooms.forEach { runCatching { syncEngine.pull(it.roomId) } }
        return me
    }

    suspend fun room(roomId: UUID): Local<Room>? = store.get(EntityType.Room, roomId)
}
