package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateDecisionRequest
import app.qichi.shared.api.Decision
import app.qichi.shared.api.DecisionConcern
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateDecisionRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** 决定记录：先写本机再经发件箱发出；修改只发改动的字段，关注点只改自己的。 */
class DecisionRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    fun observeDecisions(roomId: UUID): Flow<List<Local<Decision>>> =
        db.entities().observeByType(roomId.toString(), EntityType.Decision.wireName).map { rows -> rows.map { LocalStore.toLocal<Decision>(it) } }

    /** 备选去掉空白与重复，和服务端同样的规则。 */
    fun cleanOptions(raw: List<String>): List<String> =
        raw.map { it.trim().take(Limits.DECISION_OPTION_LENGTH.last) }.filter { it.isNotEmpty() }.distinct().take(Limits.DECISION_OPTIONS_MAX)

    suspend fun create(roomId: UUID, rawQuestion: String, rawOptions: List<String>, reviewDate: LocalDate?): Decision? {
        val question = rawQuestion.trim().take(Limits.DECISION_QUESTION_LENGTH.last).ifEmpty { return null }
        val options = cleanOptions(rawOptions)
        val now = clock.instant()
        val d = Decision(UuidV7.generate(), roomId, 0, now, now, null, null, question, options, emptyList(), null, null, null, reviewDate, me)
        store.writeLocal(roomId, d, OutboxOp.post("rooms/$roomId/decisions", CreateDecisionRequest(d.id, question, options, reviewDate)))
        scheduler.kickOutbox()
        return d
    }

    /** 本机先按服务端的规则改好再发出。 */
    suspend fun update(d: Decision, change: UpdateDecisionRequest) {
        if (change == UpdateDecisionRequest()) return
        val now = clock.instant()
        var next = d.copy(updatedAt = now)
        (change.question as? Patch.Value)?.let { next = next.copy(question = it.value) }
        (change.options as? Patch.Value)?.let { next = next.copy(options = it.value) }
        (change.myConcern as? Patch.Value)?.let { v ->
            val text = v.value?.trim().orEmpty()
            val others = next.concerns.filter { it.userId != me }
            next = next.copy(concerns = if (text.isEmpty()) others else others + DecisionConcern(me, text))
        }
        (change.finalChoice as? Patch.Value)?.let { v ->
            val choice = v.value?.trim()?.ifEmpty { null }
            next = next.copy(finalChoice = choice, decidedAt = choice?.let { now }, decidedBy = choice?.let { me })
        }
        (change.reviewDate as? Patch.Value)?.let { next = next.copy(reviewDate = it.value) }
        store.writeLocal(d.roomId, next, OutboxOp.patch("rooms/${d.roomId}/decisions/${d.id}", change))
        scheduler.kickOutbox()
    }

    suspend fun delete(d: Decision) {
        store.writeLocal(d.roomId, d.copy(deletedAt = clock.instant(), deletedBy = me), OutboxOp.delete("rooms/${d.roomId}/decisions/${d.id}"))
        scheduler.kickOutbox()
    }
}
