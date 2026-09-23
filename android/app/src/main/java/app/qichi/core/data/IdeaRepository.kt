package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.Idea
import app.qichi.shared.api.UpdateIdeaRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/** 灵感：随手记，先写本机再经发件箱发出，离线也能记。 */
class IdeaRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    /** 房间里的灵感（不含回收站），最新的在前。 */
    fun observeIdeas(roomId: UUID): Flow<List<Local<Idea>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Idea.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Idea>(it) }.sortedByDescending { it.value.createdAt } }

    /** 空白不记；超长截到上限。 */
    suspend fun add(roomId: UUID, text: String): Idea? {
        val body = text.trim().take(Limits.IDEA_BODY_LENGTH.last).ifEmpty { return null }
        val now = clock.instant()
        val idea = Idea(UuidV7.generate(), roomId, 0, now, now, null, null, me, body)
        store.writeLocal(roomId, idea, OutboxOp.post("rooms/$roomId/ideas", CreateIdeaRequest(idea.id, body)))
        scheduler.kickOutbox()
        return idea
    }

    /** 只有作者能改（界面上只给作者编辑入口）。 */
    suspend fun edit(idea: Idea, text: String) {
        val body = text.trim().take(Limits.IDEA_BODY_LENGTH.last)
        if (body.isEmpty() || body == idea.body) return
        store.writeLocal(idea.roomId, idea.copy(body = body, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${idea.roomId}/ideas/${idea.id}", UpdateIdeaRequest(body)))
        scheduler.kickOutbox()
    }

    suspend fun delete(idea: Idea) {
        val now = clock.instant()
        store.writeLocal(idea.roomId, idea.copy(deletedAt = now, deletedBy = me), OutboxOp.delete("rooms/${idea.roomId}/ideas/${idea.id}"))
        scheduler.kickOutbox()
    }
}
