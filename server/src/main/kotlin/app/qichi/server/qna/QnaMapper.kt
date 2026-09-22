package app.qichi.server.qna

import app.qichi.server.db.Answers
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.shared.api.Answer
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.model.QuestionSource
import app.qichi.shared.model.fromWire
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select

fun ResultRow.toQuestion() = Question(
    this[Questions.id], this[Questions.roomId], this[Questions.seq],
    this[Questions.createdAt], this[Questions.updatedAt], this[Questions.deletedAt], this[Questions.deletedBy],
    this[Questions.text], fromWire<QuestionSource>(this[Questions.questionSource]), this[Questions.createdBy],
    this[Questions.suggestedByJobId], this[Questions.adoptedBy], this[Questions.adoptedAt],
)

fun ResultRow.toAnswer() = Answer(
    this[Answers.id], this[Answers.roomId], this[Answers.seq],
    this[Answers.createdAt], this[Answers.updatedAt], this[Answers.deletedAt], this[Answers.deletedBy],
    this[Answers.roundId], this[Answers.authorId], this[Answers.body], this[Answers.confirmedAt],
)

fun ResultRow.toQnaRound(): QnaRound {
    val id = this[QnaRounds.id]
    return QnaRound(
        id, this[QnaRounds.roomId], this[QnaRounds.seq],
        this[QnaRounds.createdAt], this[QnaRounds.updatedAt], this[QnaRounds.deletedAt], this[QnaRounds.deletedBy],
        this[QnaRounds.questionId], this[QnaRounds.roundDate], this[QnaRounds.revealedAt],
        Answers.select(Answers.authorId).where { (Answers.roundId eq id) and Answers.confirmedAt.isNotNull() }
            .orderBy(Answers.authorId, SortOrder.ASC)
            .map { it[Answers.authorId] },
    )
}
