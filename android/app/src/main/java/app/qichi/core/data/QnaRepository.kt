package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.network.post
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.Answer
import app.qichi.shared.api.CreateQuestionRequest
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.QnaToday
import app.qichi.shared.api.Question
import app.qichi.shared.api.QuestionSuggestRequest
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.QuestionSource
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/** 问答读本机 Room；普通写操作先写 Room 与发件箱，同一个事务。 */
class QnaRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val sync: SyncEngine,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    fun observeQuestions(roomId: UUID): Flow<List<Local<Question>>> = observe(roomId, EntityType.Question)
    fun observeRounds(roomId: UUID): Flow<List<Local<QnaRound>>> = observe(roomId, EntityType.QnaRound)
    fun observeAnswers(roomId: UUID): Flow<List<Local<Answer>>> = observe(roomId, EntityType.Answer)

    private inline fun <reified T : app.qichi.shared.api.SyncEntity> observe(roomId: UUID, type: EntityType): Flow<List<Local<T>>> =
        db.entities().observeByType(roomId.toString(), type.wireName)
            .map { rows -> rows.map { LocalStore.toLocal<T>(it) } }

    /** GET 会懒创建今日轮次；响应只用于写进本机数据库，界面依旧只订阅 Room。 */
    suspend fun refreshToday(roomId: UUID) {
        val today = api.get<QnaToday>("rooms/$roomId/qna/today")
        db.transaction {
            store.applyServer(today.question)
            store.applyServer(today.round)
            today.myAnswer?.let { store.applyServer(it) }
            today.partnerAnswer?.let { store.applyServer(it) }
            today.yesterdayQuestion?.let { store.applyServer(it) }
            today.yesterday?.let { store.applyServer(it) }
            today.yesterdayAnswers.forEach { store.applyServer(it) }
        }
        sync.pull(roomId)
    }

    suspend fun createQuestion(roomId: UUID, rawText: String): Question {
        val text = rawText.trim()
        require(text.length in Limits.QUESTION_TEXT_LENGTH)
        val now = clock.instant()
        val question = Question(UuidV7.generate(), roomId, 0, now, now, null, null,
            text, QuestionSource.User, me, null, me, now)
        store.writeLocal(roomId, question, OutboxOp.post("rooms/$roomId/questions", CreateQuestionRequest(question.id, text)))
        scheduler.kickOutbox()
        return question
    }

    suspend fun writeAnswer(round: QnaRound, existing: Answer?, rawBody: String): Answer {
        val body = rawBody.trim()
        require(body.length in Limits.ANSWER_BODY_LENGTH)
        val now = clock.instant()
        val answer = existing?.copy(body = body, updatedAt = now) ?: Answer(
            UuidV7.generate(), round.roomId, 0, now, now, null, null, round.id, me, body, null,
        )
        store.writeLocal(round.roomId, answer,
            OutboxOp.put("rooms/${round.roomId}/qna/rounds/${round.id}/answer", WriteAnswerRequest(answer.id, body)))
        scheduler.kickOutbox()
        return answer
    }

    suspend fun confirm(round: QnaRound) {
        if (me in round.confirmedBy) return
        val updated = round.copy(confirmedBy = round.confirmedBy + me, updatedAt = clock.instant())
        store.writeLocal(round.roomId, updated,
            OutboxOp.action("rooms/${round.roomId}/qna/rounds/${round.id}/confirm"))
        scheduler.kickOutbox()
    }

    suspend fun adopt(question: Question) {
        if (question.adoptedAt != null) return
        val now = clock.instant()
        store.writeLocal(question.roomId, question.copy(adoptedBy = me, adoptedAt = now, updatedAt = now),
            OutboxOp.action("rooms/${question.roomId}/questions/${question.id}/adopt"))
        scheduler.kickOutbox()
    }

    suspend fun delete(question: Question) {
        if (question.deletedAt != null) return
        val now = clock.instant()
        store.writeLocal(question.roomId, question.copy(deletedAt = now, deletedBy = me, updatedAt = now),
            OutboxOp.delete("rooms/${question.roomId}/questions/${question.id}"))
        scheduler.kickOutbox()
    }

    /** AI 请求必须在线且不进发件箱。 */
    suspend fun suggest(roomId: UUID): AiJobAccepted =
        api.post("rooms/$roomId/ai/question-suggest", QuestionSuggestRequest(UuidV7.generate()))
}
