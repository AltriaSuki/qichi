package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.CreateSummaryRequest
import app.qichi.shared.api.Summary
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.SummaryKind
import app.qichi.shared.model.wireName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** 总结：生成是 AI 请求（需要联网，不进发件箱），结果同步回来；删除照常先写本机。 */
class SummaryRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    fun observeSummaries(roomId: UUID): Flow<List<Local<Summary>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Summary.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<Summary>(it) }.sortedByDescending { it.value.createdAt } }

    suspend fun generate(roomId: UUID, jobId: UUID, kind: SummaryKind, anchor: LocalDate? = null, start: LocalDate? = null, end: LocalDate? = null): AiJobAccepted =
        api.post("rooms/$roomId/summaries", CreateSummaryRequest(jobId, kind, anchor, start, end))

    suspend fun delete(s: Summary) {
        if (s.locked) return
        store.writeLocal(s.roomId, s.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${s.roomId}/summaries/${s.id}"))
        scheduler.kickOutbox()
    }
}
