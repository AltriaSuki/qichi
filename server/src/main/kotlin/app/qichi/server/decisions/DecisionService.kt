package app.qichi.server.decisions

import app.qichi.server.db.Decisions
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateDecisionRequest
import app.qichi.shared.api.Decision
import app.qichi.shared.api.DecisionConcern
import app.qichi.shared.api.Patch
import app.qichi.shared.api.UpdateDecisionRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toDecision() = Decision(
    id = this[Decisions.id], roomId = this[Decisions.roomId], seq = this[Decisions.seq],
    createdAt = this[Decisions.createdAt], updatedAt = this[Decisions.updatedAt],
    deletedAt = this[Decisions.deletedAt], deletedBy = this[Decisions.deletedBy],
    question = this[Decisions.question], options = this[Decisions.optionList], concerns = this[Decisions.concerns],
    finalChoice = this[Decisions.finalChoice], decidedAt = this[Decisions.decidedAt], decidedBy = this[Decisions.decidedBy],
    reviewDate = this[Decisions.reviewDate], createdBy = this[Decisions.createdBy],
)

/**
 * 决定记录（P6-02）：问题、备选、各自关注点（只能改自己的）、最终决定、复查日期。
 * 字段级「后到者生效」；两位成员都能改、都能删（进回收站）。
 */
class DecisionService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun decision(id: UUID): Decision? = Decisions.selectAll().where { Decisions.id eq id }.singleOrNull()?.toDecision()

    private fun checkQuestion(raw: String): String {
        val q = raw.trim()
        validate { check(q.length in Limits.DECISION_QUESTION_LENGTH, "question", "问题 1–${Limits.DECISION_QUESTION_LENGTH.last} 字") }
        return q
    }

    /** 去掉空白项和重复项；数量、长度超限 400。 */
    private fun checkOptions(raw: List<String>): List<String> {
        val options = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        validate {
            check(options.size <= Limits.DECISION_OPTIONS_MAX, "options", "最多 ${Limits.DECISION_OPTIONS_MAX} 个备选")
            check(options.all { it.length in Limits.DECISION_OPTION_LENGTH }, "options", "每个备选 1–${Limits.DECISION_OPTION_LENGTH.last} 字")
        }
        return options
    }

    suspend fun list(userId: UUID, roomId: UUID): List<Decision> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }.map { it.toDecision() }
            .sortedWith(compareBy<Decision> { it.finalChoice != null }.thenByDescending { it.decidedAt ?: it.createdAt })
    }

    suspend fun create(userId: UUID, roomId: UUID, req: CreateDecisionRequest): Pair<Decision, Boolean> {
        val question = checkQuestion(req.question)
        val options = checkOptions(req.options)
        return db.tx {
            rooms.requireMember(roomId, userId)
            writes.create(this, roomId, userId, EntityType.Decision, req.id, Decisions, ::decision) {
                it[Decisions.question] = question
                it[Decisions.optionList] = options
                it[Decisions.concerns] = emptyList()
                it[Decisions.reviewDate] = req.reviewDate
                it[Decisions.createdBy] = userId
            }
        }
    }

    suspend fun update(userId: UUID, roomId: UUID, id: UUID, req: UpdateDecisionRequest): Decision {
        val question = (req.question as? Patch.Value)?.value?.let(::checkQuestion)
        val options = (req.options as? Patch.Value)?.value?.let(::checkOptions)
        val concern = (req.myConcern as? Patch.Value)?.let { v -> v.value?.trim().orEmpty() }
        val choice = (req.finalChoice as? Patch.Value)?.let { v -> v.value?.trim()?.ifEmpty { null } }
        validate {
            check(concern == null || concern.length <= Limits.DECISION_CONCERN_MAX, "myConcern", "关注点最多 ${Limits.DECISION_CONCERN_MAX} 字")
            check(choice == null || choice.length in Limits.DECISION_CHOICE_LENGTH, "finalChoice", "最终决定最多 ${Limits.DECISION_CHOICE_LENGTH.last} 字")
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = decision(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
            val newConcerns = concern?.let { text ->
                val others = current.concerns.filter { it.userId != userId }
                if (text.isEmpty()) others else others + DecisionConcern(userId, text)
            }
            val choiceChanged = req.finalChoice is Patch.Value && choice != current.finalChoice
            val reviewChanged = req.reviewDate is Patch.Value && (req.reviewDate as Patch.Value).value != current.reviewDate
            val changed = (question != null && question != current.question) || (options != null && options != current.options) ||
                (newConcerns != null && newConcerns != current.concerns) || choiceChanged || reviewChanged
            if (changed) {
                writes.update(this, roomId, userId, EntityType.Decision, id, Decisions) {
                    if (question != null) it[Decisions.question] = question
                    if (options != null) it[Decisions.optionList] = options
                    if (newConcerns != null) it[Decisions.concerns] = newConcerns
                    if (choiceChanged) {
                        it[Decisions.finalChoice] = choice
                        it[Decisions.decidedAt] = if (choice != null) writes.now() else null
                        it[Decisions.decidedBy] = if (choice != null) userId else null
                    }
                    if (reviewChanged) it[Decisions.reviewDate] = (req.reviewDate as Patch.Value).value
                }
            }
            decision(id)!!
        }
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Decision = db.tx {
        rooms.requireMember(roomId, userId)
        val current = decision(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Decision, id, Decisions)
        decision(id)!!
    }
}
