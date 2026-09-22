package app.qichi.shared.api

import app.qichi.shared.model.QuestionSource
import kotlinx.serialization.Serializable

@Serializable
data class Question(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val text: String, val source: QuestionSource, val createdBy: Id?,
    val suggestedByJobId: Id?, val adoptedBy: Id?, val adoptedAt: Timestamp?,
) : SyncEntity

@Serializable
data class QnaRound(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val questionId: Id, val roundDate: Day, val revealedAt: Timestamp?,
    val confirmedBy: List<Id>,
) : SyncEntity

@Serializable
data class Answer(
    override val id: Id, val roomId: Id, override val seq: Long,
    val createdAt: Timestamp, val updatedAt: Timestamp,
    val deletedAt: Timestamp?, val deletedBy: Id?,
    val roundId: Id, val authorId: Id, val body: String, val confirmedAt: Timestamp?,
) : SyncEntity

@Serializable data class CreateQuestionRequest(val id: Id, val text: String)
@Serializable data class WriteAnswerRequest(val id: Id, val body: String)
@Serializable data class QuestionSuggestRequest(val jobId: Id)
@Serializable data class QnaToday(
    val round: QnaRound, val question: Question,
    val myAnswer: Answer?, val partnerAnswer: Answer?,
    val partnerConfirmed: Boolean,
    val yesterday: QnaRound?, val yesterdayQuestion: Question?,
    val yesterdayAnswers: List<Answer>,
)
