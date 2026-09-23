package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.ArchiveRevision
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.Message
import app.qichi.shared.api.ReviseArchiveItemRequest
import app.qichi.shared.model.ArchiveKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/**
 * 档案：条目是同步实体，新建、修订都先写本机再经发件箱发出。修订带基线（当前修订号）；
 * 对方先修订过时服务端 409，本机内容保留（CONFLICT），由自己决定放弃。历次修订按需在线取。
 */
class ArchiveRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    /** 房间里的档案（不含回收站），最近修订的在前。 */
    fun observeItems(roomId: UUID): Flow<List<Local<ArchiveItem>>> =
        db.entities().observeByType(roomId.toString(), EntityType.ArchiveItem.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<ArchiveItem>(it) }.sortedByDescending { it.value.updatedAt } }

    /** 本机的一条聊天消息（作为来源；没有或已删除时为空）。 */
    suspend fun message(id: UUID): Message? = store.get<Message>(EntityType.Message, id)?.value?.takeIf { it.deletedAt == null }

    suspend fun create(roomId: UUID, kind: ArchiveKind, rawTitle: String, rawBody: String, sourceMessageId: UUID?): ArchiveItem? {
        val title = rawTitle.trim().take(Limits.ARCHIVE_TITLE_LENGTH.last).ifEmpty { return null }
        val body = rawBody.trim().take(Limits.ARCHIVE_BODY_MAX)
        val now = clock.instant()
        val item = ArchiveItem(UuidV7.generate(), roomId, 0, now, now, null, null, kind, title, body, me, 1, me, sourceMessageId)
        store.writeLocal(roomId, item, OutboxOp.post("rooms/$roomId/archive", CreateArchiveItemRequest(item.id, kind, title, body, sourceMessageId)))
        scheduler.kickOutbox()
        return item
    }

    /** 新修订：基线是当前修订号；本机先显示新内容。没有任何变化时不修订。 */
    suspend fun revise(item: ArchiveItem, rawTitle: String, rawBody: String, sourceMessageId: UUID?) {
        val title = rawTitle.trim().take(Limits.ARCHIVE_TITLE_LENGTH.last)
        val body = rawBody.trim().take(Limits.ARCHIVE_BODY_MAX)
        if (title.isEmpty() || (title == item.title && body == item.body && sourceMessageId == item.sourceMessageId)) return
        val revised = item.copy(title = title, body = body, sourceMessageId = sourceMessageId,
            currentRevision = item.currentRevision + 1, revisedBy = me, updatedAt = clock.instant())
        store.writeLocal(item.roomId, revised, OutboxOp.post("rooms/${item.roomId}/archive/${item.id}/revisions",
            ReviseArchiveItemRequest(UuidV7.generate(), item.currentRevision, title, body, sourceMessageId)))
        scheduler.kickOutbox()
    }

    suspend fun delete(item: ArchiveItem) {
        store.writeLocal(item.roomId, item.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${item.roomId}/archive/${item.id}"))
        scheduler.kickOutbox()
    }

    suspend fun retry(item: ArchiveItem) {
        store.retry(EntityType.ArchiveItem, item.id)
        scheduler.kickOutbox()
    }

    /** 放弃发不出去（或修订冲突）的修改：回到服务端的样子。 */
    suspend fun abandon(item: ArchiveItem) = store.abandon(EntityType.ArchiveItem, item.id)

    /** 历次修订（新的在前，在线取）。 */
    suspend fun revisions(item: ArchiveItem): List<ArchiveRevision> = api.get("rooms/${item.roomId}/archive/${item.id}/revisions")
}
