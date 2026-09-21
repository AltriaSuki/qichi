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
import java.time.ZoneId
import java.util.UUID

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

    private suspend fun enterRoom(roomId: UUID) {
        profile.setCurrentRoom(roomId)
        refreshMe()
        syncEngine.pull(roomId)
    }

    fun observeRoom(roomId: UUID): Flow<Room?> =
        db.entities().observe(EntityType.Room.wireName, roomId.toString())
            .map { row -> row?.let { LocalStore.toLocal<Room>(it).value } }

    fun observeMembers(roomId: UUID): Flow<List<Member>> =
        db.entities().observeByType(roomId.toString(), EntityType.Member.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Member>(it).value } }

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
