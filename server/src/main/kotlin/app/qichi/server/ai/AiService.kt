package app.qichi.server.ai

import app.qichi.server.config.AiConfig
import app.qichi.server.db.AiJobs
import app.qichi.server.db.Messages
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.tx
import app.qichi.server.jobs.JobQueue
import app.qichi.server.jobs.QueuedJob
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.server.sync.RealtimeHub
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiUsage
import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.MessageRules
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

private val log = LoggerFactory.getLogger(AiService::class.java)

/**
 * AI 请求的统一模式（docs/04-api.md §3）：接口先记一条 ai_jobs 并入队，立即 202；
 * 后台任务调用大模型，把结果写进对应实体（问 AI 是一条 id = jobId 的 AI 消息），再发 ai.done。
 * 额度按整个服务每月的 token 总量算（AI_MONTHLY_TOKEN_LIMIT），用完返回 ai_quota_exceeded。
 */
class AiService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writer: RoomWriter,
    private val queue: JobQueue,
    private val gateway: AiGateway?,
    private val config: AiConfig,
    private val realtime: RealtimeHub,
    private val clock: Clock,
    private val prompts: Prompts = Prompts(),
) {
    val enabled: Boolean get() = gateway != null

    init {
        queue.register(JOB_CHAT) { job -> answerInChat(job) }
    }

    /** 问 AI（聊天里）。同一 jobId 再次请求：失败的重新排队，其余返回当前状态。 */
    suspend fun askInChat(userId: UUID, roomId: UUID, req: AiChatRequest): AiJobAccepted {
        val prompt = req.prompt.trim()
        validate { check(prompt.length in 1..PROMPT_MAX, "prompt", "问题 1–$PROMPT_MAX 字") }
        if (gateway == null) throw unavailable()
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            val existing = AiJobs.selectAll().where { AiJobs.id eq req.jobId }.singleOrNull()
            if (existing != null) {
                if (existing[AiJobs.roomId] != roomId || existing[AiJobs.requestedBy] != userId) {
                    throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                }
                val status = fromWire<AiJobStatus>(existing[AiJobs.status])
                if (status != AiJobStatus.Failed) return@tx AiJobAccepted(req.jobId, status)
                checkQuota()
                AiJobs.update({ AiJobs.id eq req.jobId }) {
                    it[AiJobs.status] = AiJobStatus.Queued.wireName
                    it[error] = null
                    it[finishedAt] = null
                    it[updatedAt] = clock.instant()
                }
            } else {
                checkQuota()
                val now = clock.instant()
                AiJobs.insert {
                    it[id] = req.jobId
                    it[AiJobs.roomId] = roomId
                    it[requestedBy] = userId
                    it[kind] = AiJobKind.ChatAnswer.wireName
                    it[status] = AiJobStatus.Queued.wireName
                    it[request] = buildJsonObject { put("prompt", prompt) }
                    it[inputTokens] = 0
                    it[outputTokens] = 0
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            queue.enqueue(this, JOB_CHAT, buildJsonObject { put("aiJobId", req.jobId.toString()) }, maxAttempts = CHAT_ATTEMPTS)
            AiJobAccepted(req.jobId, AiJobStatus.Queued)
        }
    }

    suspend fun job(userId: UUID, roomId: UUID, jobId: UUID): AiJob = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        AiJobs.selectAll().where { (AiJobs.id eq jobId) and (AiJobs.roomId eq roomId) }.singleOrNull()?.toAiJob() ?: notFound()
    }

    /** 「我发起的 AI 使用」：某个月（UTC）我发起的调用，以及本月整个服务的用量与上限。 */
    suspend fun usage(userId: UUID, month: YearMonth?): AiUsage {
        val now = clock.instant()
        val target = month ?: YearMonth.from(now.atOffset(ZoneOffset.UTC))
        val from = target.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val to = target.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        return db.tx(readOnly = true) {
            val jobs = AiJobs.selectAll()
                .where { (AiJobs.requestedBy eq userId) and (AiJobs.createdAt greaterEq from) and (AiJobs.createdAt less to) }
                .orderBy(AiJobs.createdAt, SortOrder.DESC)
                .map { it.toAiJob() }
            AiUsage(
                month = target.toString(),
                jobs = jobs,
                myInputTokens = jobs.sumOf { it.inputTokens.toLong() },
                myOutputTokens = jobs.sumOf { it.outputTokens.toLong() },
                monthUsedTokens = monthUsed(now),
                monthLimitTokens = config.monthlyTokenLimit,
            )
        }
    }

    /** 后台任务：带上最近的聊天内容请模型回答，写成一条 AI 消息。 */
    private suspend fun answerInChat(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy]
        val prompt = row[AiJobs.request]["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        markRunning(jobId)

        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")
        val context = db.tx(readOnly = true) { chatContext(roomId) }
        val names = db.tx(readOnly = true) { RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName } }
        val rendered = prompts.render(
            "chat_answer",
            mapOf(
                "asker" to (names[askerId] ?: "提问的人"),
                "history" to context.joinToString("\n") { m -> "${m.authorId?.let(names::get) ?: "AI"}：${m.text}" }.ifEmpty { "（还没有聊天记录）" },
                "prompt" to prompt,
            ),
        )
        val result = try {
            gateway.complete(AiRequest(rendered.system, listOf(AiMessage(AiMessage.Role.User, rendered.user)), maxTokens = CHAT_MAX_TOKENS))
        } catch (e: AiProviderException) {
            log.warn("问 AI 失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            return fail(jobId, roomId, "没有得到回答")
        }

        db.tx {
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Message, jobId, askerId, now)
            Messages.insert {
                it[id] = jobId
                it[Messages.roomId] = roomId
                it[Messages.seq] = seq
                it[createdSeq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[authorId] = null
                it[kind] = MessageKind.Ai.wireName
                it[body] = result.text.take(MESSAGE_MAX)
                it[aiPrompt] = prompt
            }
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                it[resultRef] = "message:$jobId"
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    private data class ContextLine(val authorId: UUID?, val text: String)

    /** 最近 [CONTEXT_MESSAGES] 条没撤回、没删除的消息，从早到晚。 */
    private fun chatContext(roomId: UUID): List<ContextLine> =
        messageQuery()
            .where { (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() }
            .orderBy(Messages.createdSeq, SortOrder.DESC)
            .limit(CONTEXT_MESSAGES)
            .map { it.toMessage() }
            .reversed()
            .mapNotNull { m ->
                val text = if (m.kind == MessageKind.Ai) m.body else MessageRules.replyExcerpt(m.kind, m.body, m.file?.fileName, false)
                text?.takeIf { it.isNotBlank() }?.let { ContextLine(m.authorId, it.take(CONTEXT_LINE_MAX)) }
            }

    private suspend fun markRunning(jobId: UUID) = db.tx {
        AiJobs.update({ AiJobs.id eq jobId }) {
            it[status] = AiJobStatus.Running.wireName
            it[updatedAt] = clock.instant()
        }
    }

    private suspend fun fail(jobId: UUID, roomId: UUID, reason: String) {
        db.tx {
            val now = clock.instant()
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Failed.wireName
                it[error] = reason
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Failed.wireName)
    }

    /** 本月（UTC）整个服务已用的 token。 */
    private fun monthUsed(now: Instant): Long {
        val month = YearMonth.from(now.atOffset(ZoneOffset.UTC))
        val from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val total = (AiJobs.inputTokens + AiJobs.outputTokens).sum()
        return AiJobs.select(total).where { AiJobs.createdAt greaterEq from }.single()[total]?.toLong() ?: 0L
    }

    private fun checkQuota() {
        if (monthUsed(clock.instant()) >= config.monthlyTokenLimit) {
            throw ApiException(ProblemCode.AiQuotaExceeded, "这个月的 AI 额度用完了", detail = "下个月 1 号恢复")
        }
    }

    private fun unavailable() = ApiException(ProblemCode.AiUnavailable, "AI 还没有开启", detail = "需要在服务器上配置 AI（docs/07-deploy.md）")

    private fun ResultRow.toAiJob() = AiJob(
        id = this[AiJobs.id],
        roomId = this[AiJobs.roomId],
        kind = fromWire(this[AiJobs.kind]),
        status = fromWire(this[AiJobs.status]),
        model = this[AiJobs.model],
        inputTokens = this[AiJobs.inputTokens],
        outputTokens = this[AiJobs.outputTokens],
        resultRef = this[AiJobs.resultRef],
        error = this[AiJobs.error],
        createdAt = this[AiJobs.createdAt],
        finishedAt = this[AiJobs.finishedAt],
    )

    companion object {
        const val JOB_CHAT = "ai.chat"
        const val PROMPT_MAX = 2000
        private const val CHAT_ATTEMPTS = 2
        private const val CHAT_MAX_TOKENS = 800
        private const val CONTEXT_MESSAGES = 30
        private const val CONTEXT_LINE_MAX = 300
        private const val MESSAGE_MAX = 10_000
    }
}
