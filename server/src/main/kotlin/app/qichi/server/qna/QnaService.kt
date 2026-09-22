package app.qichi.server.qna

import app.qichi.server.db.Answers
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Rooms
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.Answer
import app.qichi.shared.api.CreateQuestionRequest
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.QnaToday
import app.qichi.shared.api.Question
import app.qichi.shared.api.WriteAnswerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.QuestionSource
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class QnaService(
    private val db: QichiDatabase, private val rooms: RoomService,
    private val writes: EntityWrites, private val writer: RoomWriter, private val clock: Clock,
) {
    suspend fun questions(userId: UUID, roomId: UUID, status: String): List<Question> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        validate { check(status == "adopted" || status == "suggested", "status", "只能是 adopted 或 suggested") }
        Questions.selectAll().where { (Questions.roomId eq roomId) and Questions.deletedAt.isNull() }
            .orderBy(Questions.createdAt, SortOrder.DESC).map { it.toQuestion() }
            .filter { (it.adoptedAt != null) == (status == "adopted") }
    }

    suspend fun createQuestion(userId: UUID, roomId: UUID, request: CreateQuestionRequest): Pair<Question, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        val text = request.text.trim()
        validate { check(text.length in Limits.QUESTION_TEXT_LENGTH, "text", "题目 1–500 字") }
        writes.create(this, roomId, userId, EntityType.Question, request.id, Questions,
            { id -> Questions.selectAll().where { Questions.id eq id }.singleOrNull()?.toQuestion() }) {
            it[Questions.text] = text
            it[Questions.questionSource] = QuestionSource.User.wireName
            it[Questions.createdBy] = userId
            it[Questions.suggestedByJobId] = null
            it[Questions.adoptedBy] = userId
            it[Questions.adoptedAt] = writes.now()
        }
    }

    suspend fun adopt(userId: UUID, roomId: UUID, id: UUID): Question = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Questions.selectAll().where { (Questions.id eq id) and (Questions.roomId eq roomId) and Questions.deletedAt.isNull() }.singleOrNull() ?: notFound()
        if (row[Questions.adoptedAt] == null) writes.update(this, roomId, userId, EntityType.Question, id, Questions) {
            it[Questions.adoptedBy] = userId
            it[Questions.adoptedAt] = writes.now()
        }
        Questions.selectAll().where { Questions.id eq id }.single().toQuestion()
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Question = db.tx {
        rooms.requireMember(roomId, userId)
        val row = Questions.selectAll().where { (Questions.id eq id) and (Questions.roomId eq roomId) }.singleOrNull() ?: notFound()
        if (row[Questions.deletedAt] == null) writes.softDelete(this, roomId, userId, EntityType.Question, id, Questions)
        Questions.selectAll().where { Questions.id eq id }.single().toQuestion()
    }

    suspend fun today(userId: UUID, roomId: UUID): QnaToday = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val zone = ZoneId.of(Rooms.selectAll().where { Rooms.id eq roomId }.single()[Rooms.timezone])
        val date = LocalDate.ofInstant(clock.instant(), zone)
        val round = QnaRounds.selectAll().where { (QnaRounds.roomId eq roomId) and (QnaRounds.roundDate eq date) }
            .singleOrNull()?.toQnaRound() ?: makeRound(roomId, date)
        val question = Questions.selectAll().where { Questions.id eq round.questionId }.single().toQuestion()
        val answers = Answers.selectAll().where { Answers.roundId eq round.id }.map { it.toAnswer() }
        val yesterday = QnaRounds.selectAll().where { (QnaRounds.roomId eq roomId) and (QnaRounds.roundDate eq date.minusDays(1)) }
            .singleOrNull()?.toQnaRound()
        val yesterdayQuestion = yesterday?.let { Questions.selectAll().where { Questions.id eq it.questionId }.single().toQuestion() }
        val yesterdayAnswers = yesterday?.let { prior ->
            Answers.selectAll().where { Answers.roundId eq prior.id }.map { it.toAnswer() }
                .filter { it.authorId == userId || prior.revealedAt != null }
        } ?: emptyList()
        QnaToday(round, question, answers.firstOrNull { it.authorId == userId },
            answers.firstOrNull { it.authorId != userId }?.takeIf { round.revealedAt != null },
            answers.any { it.authorId != userId && it.confirmedAt != null },
            yesterday, yesterdayQuestion, yesterdayAnswers)
    }

    private fun app.qichi.server.db.Tx.makeRound(roomId: UUID, date: LocalDate): QnaRound {
        val used = QnaRounds.selectAll().where { QnaRounds.roomId eq roomId }.map { it[QnaRounds.questionId] }.toSet()
        val adopted = Questions.selectAll().where { (Questions.roomId eq roomId) and Questions.deletedAt.isNull() and Questions.adoptedAt.isNotNull() }
            .map { it.toQuestion() }.sortedWith(compareBy<Question> { it.createdAt }.thenBy { it.id })
        val choice = adopted.firstOrNull { it.source != QuestionSource.Preset && it.id !in used } ?: preset(roomId, used)
            ?: adopted.minByOrNull { question ->
                QnaRounds.selectAll().where { QnaRounds.questionId eq question.id }.map { it[QnaRounds.roundDate] }.maxOrNull() ?: LocalDate.MIN
            } ?: error("预置题库为空")
        val id = UuidV7.generate()
        val now = clock.instant()
        val seq = writer.change(this, roomId, EntityType.QnaRound, id, null, now)
        QnaRounds.insert {
            it[QnaRounds.id] = id; it[QnaRounds.roomId] = roomId; it[QnaRounds.seq] = seq
            it[createdAt] = now; it[updatedAt] = now; it[questionId] = choice.id; it[roundDate] = date
        }
        return QnaRounds.selectAll().where { QnaRounds.id eq id }.single().toQnaRound()
    }

    private fun app.qichi.server.db.Tx.preset(roomId: UUID, used: Set<UUID>): Question? {
        val lines = javaClass.getResourceAsStream("/qna/presets.txt")?.bufferedReader()?.use { it.readLines() } ?: return null
        for (line in lines.filter { it.isNotBlank() }) {
            val existing = Questions.selectAll().where { (Questions.roomId eq roomId) and (Questions.questionSource eq QuestionSource.Preset.wireName) and (Questions.text eq line) }
                .singleOrNull()?.toQuestion()
            if (existing != null) {
                if (existing.id !in used && existing.deletedAt == null) return existing
                continue
            }
            val id = UuidV7.generate()
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Question, id, null, now)
            Questions.insert {
                it[Questions.id] = id; it[Questions.roomId] = roomId; it[Questions.seq] = seq
                it[createdAt] = now; it[updatedAt] = now; it[text] = line; it[questionSource] = QuestionSource.Preset.wireName
                it[adoptedAt] = now
            }
            return Questions.selectAll().where { Questions.id eq id }.single().toQuestion()
        }
        return null
    }

    suspend fun answer(userId: UUID, roomId: UUID, roundId: UUID, request: WriteAnswerRequest): Pair<Answer, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val round = QnaRounds.selectAll().where { (QnaRounds.id eq roundId) and (QnaRounds.roomId eq roomId) }.singleOrNull() ?: notFound()
        val body = request.body.trim()
        validate { check(body.length in Limits.ANSWER_BODY_LENGTH, "body", "回答 1–10000 字") }
        val existing = Answers.selectAll().where { (Answers.roundId eq roundId) and (Answers.authorId eq userId) }.singleOrNull()
        if (existing != null) {
            if (existing[Answers.id] != request.id) throw ApiException(ProblemCode.ConflictId, "这轮已有回答")
            if (existing[Answers.confirmedAt] != null || round[QnaRounds.revealedAt] != null) {
                if (existing[Answers.body] != body) throw ApiException(ProblemCode.ConflictVersion, "答案已经确认")
                return@tx existing.toAnswer() to false
            }
            if (existing[Answers.body] != body) writes.update(this, roomId, userId, EntityType.Answer, request.id, Answers) { it[Answers.body] = body }
            Answers.selectAll().where { Answers.id eq request.id }.single().toAnswer() to false
        } else {
            if (round[QnaRounds.revealedAt] != null) throw ApiException(ProblemCode.ConflictVersion, "答案已经揭晓")
            val sameId = Answers.selectAll().where { Answers.id eq request.id }.singleOrNull()
            if (sameId != null) throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            writes.create(this, roomId, userId, EntityType.Answer, request.id, Answers,
                { id -> Answers.selectAll().where { Answers.id eq id }.singleOrNull()?.toAnswer() }) {
                it[Answers.roundId] = roundId; it[Answers.authorId] = userId; it[Answers.body] = body
            }
        }
    }

    suspend fun confirm(userId: UUID, roomId: UUID, roundId: UUID): QnaRound = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        val round = QnaRounds.selectAll().where { (QnaRounds.id eq roundId) and (QnaRounds.roomId eq roomId) }.singleOrNull() ?: notFound()
        val mine = Answers.selectAll().where { (Answers.roundId eq roundId) and (Answers.authorId eq userId) }.singleOrNull() ?: notFound()
        if (mine[Answers.confirmedAt] == null) {
            writes.update(this, roomId, userId, EntityType.Answer, mine[Answers.id], Answers) { it[Answers.confirmedAt] = writes.now() }
            writes.update(this, roomId, userId, EntityType.QnaRound, roundId, QnaRounds) {}
        }
        val confirmed = Answers.selectAll().where { (Answers.roundId eq roundId) and Answers.confirmedAt.isNotNull() }.toList()
        val members = RoomRepository.activeMembers(roomId)
        if (round[QnaRounds.revealedAt] == null && members.size == 2 &&
            confirmed.map { it[Answers.authorId] }.toSet() == members.map { it.userId }.toSet()) {
            writes.update(this, roomId, userId, EntityType.QnaRound, roundId, QnaRounds) { it[QnaRounds.revealedAt] = writes.now() }
            confirmed.forEach { row -> writes.update(this, roomId, userId, EntityType.Answer, row[Answers.id], Answers) {} }
        }
        QnaRounds.selectAll().where { QnaRounds.id eq roundId }.single().toQnaRound()
    }
}
