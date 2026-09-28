package app.qichi.server.ai

import app.qichi.server.ai.tools.RoomTools
import app.qichi.server.ai.tools.SourceBook
import app.qichi.server.config.AiConfig
import app.qichi.server.db.AiActions
import app.qichi.server.db.AiFindings
import app.qichi.server.db.AiJobs
import app.qichi.server.db.Books
import app.qichi.server.db.Documents
import app.qichi.server.db.Highlights
import app.qichi.server.db.Jobs
import app.qichi.server.db.Messages
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Questions
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.ReviewPages
import app.qichi.server.db.ReviewVersions
import app.qichi.server.db.RoomWriter
import app.qichi.server.db.Rooms
import app.qichi.server.db.Summaries
import app.qichi.server.db.Tx
import app.qichi.server.db.Users
import app.qichi.server.db.tx
import app.qichi.server.jobs.JobLane
import app.qichi.server.jobs.JobQueue
import app.qichi.server.jobs.QueuedJob
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.reading.toHighlight
import app.qichi.server.review.FindingParser
import app.qichi.server.review.toAiFinding
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.server.summaries.SourceLine
import app.qichi.server.summaries.SummaryData
import app.qichi.server.sync.RealtimeHub
import app.qichi.server.sync.Visibility
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.AiJob
import app.qichi.shared.api.AiJobAccepted
import app.qichi.shared.api.AiPrefs
import app.qichi.shared.api.AiReadExplainRequest
import app.qichi.shared.api.AiReviewFindingsRequest
import app.qichi.shared.api.AiUsage
import app.qichi.shared.api.AiWriteRequest
import app.qichi.shared.api.CreateSummaryRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.QuestionSuggestRequest
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.model.AiActionStatus
import app.qichi.shared.model.AiJobKind
import app.qichi.shared.model.AiFailReason
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.DraftGenre
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FindingStatus
import app.qichi.shared.model.HighlightKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.QuestionSource
import app.qichi.shared.model.ReadExplainMode
import app.qichi.shared.model.SummaryKind
import app.qichi.shared.model.WriteAssistMode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.util.UuidV7
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
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

    /** 正在流式生成的问 AI（jobId → 调用模型的协程），「停下」时取消它（P10-04）。只有一个服务端进程，放内存里就够。 */
    private val streaming = ConcurrentHashMap<UUID, Job>()

    /** 提问的人点了「停下」、还没收尾的任务。 */
    private val stopRequested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    init {
        // 队列彻底放弃（出了预料之外的错、执行中断次数用完）时由 giveUp 把 ai_jobs 标成失败，App 不会一直等（P13-03）
        queue.register(JOB_CHAT, ::giveUp, JobLane.Ai) { job -> answerInChat(job) }
        queue.register(JOB_QUESTION, ::giveUp, JobLane.Ai) { job -> suggestQuestion(job) }
        queue.register(JOB_READ, ::giveUp, JobLane.Ai) { job -> explainReading(job) }
        queue.register(JOB_SUMMARY, ::giveUp, JobLane.Ai) { job -> summarize(job) }
        // 年度检查放弃了也要接着排下一次，不然每年一次的回顾就断了
        queue.register(JOB_YEARLY_CHECK, { _, _ -> ensureYearlyCheck() }) { _ -> yearlyCheck() }
        queue.register(JOB_REVIEW, ::giveUp, JobLane.Ai) { job -> reviewFindings(job) }
        queue.register(JOB_WRITE, ::giveUp, JobLane.Ai) { job -> writeAssist(job) }
    }

    /** 队列放弃了这个 AI 任务：还没有结果的标成失败并通知（App 显示「没有得到回答 · 重试」，同一 jobId 可以重新排队）。 */
    private suspend fun giveUp(job: QueuedJob, reason: String) {
        val jobId = job.payload["aiJobId"]?.jsonPrimitive?.contentOrNull?.let(UUID::fromString) ?: return
        val roomId = db.tx {
            val row = AiJobs.selectAll().where { AiJobs.id eq jobId }.forUpdate().singleOrNull() ?: return@tx null
            val status = fromWire<AiJobStatus>(row[AiJobs.status])
            if (status == AiJobStatus.Done || status == AiJobStatus.Failed) return@tx null
            val now = clock.instant()
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[AiJobs.status] = AiJobStatus.Failed.wireName
                it[error] = "没有得到结果"
                it[failReason] = AiFailReason.Other.wireName
                it[finishedAt] = now
                it[updatedAt] = now
            }
            row[AiJobs.roomId]
        } ?: return
        log.warn("AI 任务 {} 放弃：{}", jobId, reason)
        stopRequested -= jobId
        realtime.aiDone(roomId, jobId, AiJobStatus.Failed.wireName, AiFailReason.Other.wireName)
    }

    /** 问 AI（聊天里）。同一 jobId 再次请求：失败的重新排队，其余返回当前状态。 */
    suspend fun askInChat(userId: UUID, roomId: UUID, req: AiChatRequest): AiJobAccepted {
        val prompt = req.prompt.trim()
        validate { check(prompt.length in 1..PROMPT_MAX, "prompt", "问题 1–$PROMPT_MAX 字") }
        return db.tx {
            rooms.requireMember(roomId, userId)
            if (gateway == null) throw unavailable()
            req.sourceMessageId?.let { sourceId ->
                val ok = Messages.select(Messages.id).where {
                    (Messages.id eq sourceId) and (Messages.roomId eq roomId) and Visibility.quotableMessage()
                }.any()
                validate { check(ok, "sourceMessageId", "要整理的消息不存在") }
            }
            RoomRepository.lockRoom(roomId)
            startJob(roomId, userId, req.jobId, AiJobKind.ChatAnswer, JOB_CHAT) {
                buildJsonObject {
                    put("prompt", prompt)
                    req.sourceMessageId?.let { id -> put("sourceMessageId", id.toString()) }
                }
            }
        }
    }

    suspend fun suggest(userId: UUID, roomId: UUID, req: QuestionSuggestRequest): AiJobAccepted {
        return db.tx {
            rooms.requireMember(roomId, userId)
            if (gateway == null) throw unavailable()
            RoomRepository.lockRoom(roomId)
            startJob(roomId, userId, req.jobId, AiJobKind.QuestionSuggest, JOB_QUESTION) { buildJsonObject { } }
        }
    }

    /**
     * 阅读里选中一段请 AI 解释、对比（P6-05），或按自己写的要求（P14-05）。同一 jobId 再次请求：失败的重新排队，其余返回当前状态。
     */
    suspend fun readExplain(userId: UUID, roomId: UUID, req: AiReadExplainRequest): AiJobAccepted {
        val text = req.text.trim()
        val instruction = req.instruction?.trim().orEmpty()
        validate {
            check(text.length in 1..READ_TEXT_MAX, "text", "选中的文字 1–$READ_TEXT_MAX 字")
            check(req.locator.length in 1..Limits.LOCATOR_MAX, "locator", "定位信息不对")
            check(req.before.length <= READ_CONTEXT_MAX && req.after.length <= READ_CONTEXT_MAX, "before", "上下文太长")
            if (req.mode == ReadExplainMode.Custom) {
                check(instruction.length in Limits.READING_PROMPT_INSTRUCTION_LENGTH, "instruction", "要求 1–300 个字")
            }
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            if (gateway == null) throw unavailable()
            RoomRepository.lockRoom(roomId)
            val bookOk = Books.select(Books.id).where { (Books.id eq req.bookId) and (Books.roomId eq roomId) and Books.deletedAt.isNull() }.any()
            if (!bookOk) notFound()
            startJob(roomId, userId, req.jobId, AiJobKind.ReadExplain, JOB_READ) {
                buildJsonObject {
                    put("bookId", req.bookId.toString())
                    put("mode", req.mode.wireName)
                    put("text", text)
                    put("locator", req.locator)
                    put("before", req.before)
                    put("after", req.after)
                    if (req.mode == ReadExplainMode.Custom) put("instruction", instruction)
                }
            }
        }
    }

    private suspend fun explainReading(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy] ?: return
        val req = row[AiJobs.request]
        fun field(name: String) = req[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bookId = UUID.fromString(field("bookId"))
        val mode = fromWire<ReadExplainMode>(field("mode"))
        markRunning(jobId)
        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")

        val now = clock.instant()
        val (book, notes, tools) = db.tx(readOnly = true) {
            val b = Books.select(Books.title, Books.author).where { Books.id eq bookId }.singleOrNull()
            val names = RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName }
            // 要联系到他们自己时能查房间资料（P14-04），和问 AI 一样守「AI 能看什么」
            val lookup = if (config.tools) RoomTools(roomId, now, roomZone(roomId), names, roomPrefs(roomId), SourceBook(), openReaders(roomId))
                .takeIf { it.definitions.isNotEmpty() } else null
            // 对比、按要求问时参考的标注：只用提问的人看得到的（自己的全部，加上对方共享的），结果也只有他看得到
            val list = Highlights.selectAll().where { (Highlights.bookId eq bookId) and Highlights.deletedAt.isNull() }
                .map { it.toHighlight() }
                .filter { Visibility.highlight(it, askerId) && (it.kind == HighlightKind.Highlight || it.kind == HighlightKind.Excerpt) }
                .sortedBy { it.createdAt }.takeLast(COMPARE_NOTES)
                .joinToString("\n") { h -> "${names[h.userId] ?: "其中一人"}：${h.text.take(CONTEXT_LINE_MAX)}" + (h.note?.let { " —— ${it.take(CONTEXT_LINE_MAX)}" } ?: "") }
            Triple(b, list, lookup)
        }
        if (book == null) return fail(jobId, roomId, "书已经不在书架上了")
        val template = when (mode) {
            ReadExplainMode.Explain -> "read_explain"
            ReadExplainMode.Compare -> "read_compare"
            ReadExplainMode.Custom -> "read_custom"
        }
        val instruction = field("instruction")
        val rendered = prompts.render(
            template,
            mapOf(
                "title" to book[Books.title],
                "author" to (book[Books.author]?.let { "（$it）" } ?: ""),
                "text" to field("text"),
                "before" to field("before").ifEmpty { "（无）" },
                "after" to field("after").ifEmpty { "（无）" },
                "notes" to notes.ifEmpty { "（还没有标注和摘录）" },
                "instruction" to instruction,
                "lookup" to if (tools != null) prompts.render("read_lookup", mapOf("rounds" to TOOL_ROUNDS.toString())).user + "\n" else "",
            ),
        )
        val result = try {
            completeLookingUp(gateway, rendered.system, rendered.user, CHAT_MAX_TOKENS, tools, "阅读 AI")
        } catch (e: AiProviderException) {
            log.warn("阅读 AI 失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            return fail(jobId, roomId, "没有得到回答", e.reason)
        }
        // 查过资料的回答也不标编号（存成标记旁边的一段话，点不开来源）；一直只想查、一个字没写时算没得到回答
        val answer = result.text.replace(CITATION, "").trim().let { if (result.truncated) it + CUT_OFF else it }
        if (answer.isEmpty()) return fail(jobId, roomId, "没有得到回答")
        db.tx {
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Highlight, jobId, askerId, now)
            Highlights.insert {
                it[id] = jobId
                it[Highlights.roomId] = roomId
                it[Highlights.seq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[Highlights.bookId] = bookId
                it[userId] = askerId
                it[kind] = HighlightKind.Ai.wireName
                it[locator] = field("locator")
                it[text] = field("text").take(Limits.HIGHLIGHT_TEXT_MAX)
                // 按自己的要求问的：开头写上问了什么，回头看时知道这段解释是怎么来的
                it[note] = (if (mode == ReadExplainMode.Custom) "问：$instruction\n\n$answer" else answer).take(Limits.HIGHLIGHT_NOTE_MAX)
                it[shared] = false
            }
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                it[resultRef] = "highlight:$jobId"
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    // ── 审稿 AI（P7-03） ──

    /** 本次授权 AI 审一个版本。版本的预览要已生成（AI 只看文字层）。 */
    suspend fun requestReviewFindings(userId: UUID, roomId: UUID, req: AiReviewFindingsRequest): AiJobAccepted = db.tx {
        rooms.requireMember(roomId, userId)
        if (gateway == null) throw unavailable()
        RoomRepository.lockRoom(roomId)
        val docOk = ReviewDocuments.select(ReviewDocuments.id)
            .where { (ReviewDocuments.id eq req.documentId) and (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }.any()
        val status = ReviewVersions.select(ReviewVersions.previewStatus)
            .where { (ReviewVersions.id eq req.versionId) and (ReviewVersions.documentId eq req.documentId) }.singleOrNull()?.get(ReviewVersions.previewStatus)
        if (!docOk || status == null) notFound()
        validate { check(status == PreviewStatus.Ready.wireName, "versionId", "预览还没生成好") }
        startJob(roomId, userId, req.jobId, AiJobKind.ReviewFindings, JOB_REVIEW) {
            buildJsonObject {
                put("documentId", req.documentId.toString())
                put("versionId", req.versionId.toString())
            }
        }
    }

    private suspend fun reviewFindings(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy] ?: return
        val req = row[AiJobs.request]
        val documentId = UUID.fromString(req["documentId"]!!.jsonPrimitive.content)
        val versionId = UUID.fromString(req["versionId"]!!.jsonPrimitive.content)
        markRunning(jobId)
        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")

        data class Input(val title: String, val version: Int, val pages: List<ReviewPage>, val known: List<AiFinding>)
        val input = db.tx(readOnly = true) {
            val title = ReviewDocuments.select(ReviewDocuments.title).where { (ReviewDocuments.id eq documentId) and ReviewDocuments.deletedAt.isNull() }
                .singleOrNull()?.get(ReviewDocuments.title) ?: return@tx null
            val version = ReviewVersions.select(ReviewVersions.version).where { ReviewVersions.id eq versionId }.singleOrNull()?.get(ReviewVersions.version) ?: return@tx null
            val pages = ReviewPages.selectAll().where { ReviewPages.versionId eq versionId }.orderBy(ReviewPages.pageNo).map {
                ReviewPage(it[ReviewPages.pageNo], it[ReviewPages.width], it[ReviewPages.height], it[ReviewPages.imageFileId], it[ReviewPages.textLayer], it[ReviewPages.images])
            }
            val known = AiFindings.selectAll().where { (AiFindings.versionId eq versionId) and AiFindings.deletedAt.isNull() }.map { it.toAiFinding() }
            Input(title, version, pages, known)
        } ?: return fail(jobId, roomId, "审稿文件已经删除了")

        // 原文：每块前面带编号；太长时只发前面一部分
        val lines = input.pages.flatMap { p -> p.blocks.filter { it.text.isNotBlank() }.map { p.page to "[${it.id}] ${it.text.replace(Regex("\\s+"), " ").trim()}" } }
        if (lines.isEmpty()) return fail(jobId, roomId, "这一版里没有能读的文字（可能是扫描件）")
        val text = StringBuilder()
        var truncated = false
        var lastPage = 0
        for ((page, line) in lines) {
            if (text.length + line.length > Limits.REVIEW_AI_TEXT_MAX) { truncated = true; break }
            text.appendLine(line)
            lastPage = page
        }
        val rendered = prompts.render(
            "review_findings",
            mapOf(
                "title" to input.title,
                "version" to input.version.toString(),
                "max" to Limits.FINDINGS_MAX.toString(),
                "truncated" to if (truncated) "（文件太长，下面只有前面一部分）\n" else "",
                "known" to input.known.filter { it.status != FindingStatus.Dismissed }.joinToString("\n") { "- ${it.title}" }.ifEmpty { "（无）" },
                "text" to text.toString().trim(),
            ),
        )
        val result = try {
            gateway.complete(AiRequest(rendered.system, listOf(AiMessage(AiMessage.Role.User, rendered.user)), maxTokens = REVIEW_MAX_TOKENS, timeoutMillis = BACKGROUND_TIMEOUT_MS))
        } catch (e: AiProviderException) {
            log.warn("审稿 AI 失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            return fail(jobId, roomId, "AI 没有给出结果", e.reason)
        }
        // 已经记下过的（证据原文一样）不再重复
        val knownQuotes = input.known.map { f -> f.evidence.map { FindingParser.compact(it.quote) }.toSet() }
        val parsed = FindingParser.parse(result.text, input.pages).filter { f -> f.evidence.map { FindingParser.compact(it.quote) }.toSet() !in knownQuotes }
        db.tx {
            val now = clock.instant()
            for (f in parsed) {
                val id = UuidV7.generate()
                val seq = writer.change(this, roomId, EntityType.AiFinding, id, askerId, now)
                AiFindings.insert {
                    it[AiFindings.id] = id
                    it[AiFindings.roomId] = roomId
                    it[AiFindings.seq] = seq
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[AiFindings.documentId] = documentId
                    it[AiFindings.versionId] = versionId
                    it[AiFindings.jobId] = jobId
                    it[requestedBy] = askerId
                    it[title] = f.title
                    it[body] = f.body
                    it[evidence] = f.evidence
                    it[status] = FindingStatus.New.wireName
                }
            }
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                // 文件太长只发了前面一部分：记下读到第几页，App 审完时告诉人
                it[resultRef] = "ai_finding:${parsed.size}" + (if (truncated) ";read_pages:$lastPage" else "")
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    // ── 总结与年度回顾（P6-06） ──

    private fun roomZone(roomId: UUID): ZoneId =
        Rooms.select(Rooms.timezone).where { Rooms.id eq roomId }.single()[Rooms.timezone].let { runCatching { ZoneId.of(it) }.getOrDefault(ZoneId.of("Asia/Shanghai")) }

    /** 周：anchor 所在的周一到周日；月：那个月；自定义：给的范围（最长 366 天）。年度回顾不能手动生成。 */
    suspend fun createSummary(userId: UUID, roomId: UUID, req: CreateSummaryRequest): AiJobAccepted {
        validate {
            check(req.kind != SummaryKind.Year, "kind", "年度回顾由服务端每年 1 月 1 日生成")
            if (req.kind == SummaryKind.Custom) {
                check(req.rangeStart != null && req.rangeEnd != null && !req.rangeStart!!.isAfter(req.rangeEnd), "rangeStart", "开始日期不能晚于结束日期")
                check(req.rangeStart == null || req.rangeEnd == null || ChronoUnit.DAYS.between(req.rangeStart, req.rangeEnd) <= 366, "rangeEnd", "最长一年")
            }
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            if (gateway == null) throw unavailable()
            RoomRepository.lockRoom(roomId)
            val today = clock.instant().atZone(roomZone(roomId)).toLocalDate()
            val anchor = req.anchor ?: today
            val (start, end) = when (req.kind) {
                SummaryKind.Week -> anchor.with(DayOfWeek.MONDAY).let { it to it.plusDays(6) }
                SummaryKind.Month -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
                else -> req.rangeStart!! to req.rangeEnd!!
            }
            startJob(roomId, userId, req.jobId, AiJobKind.Summary, JOB_SUMMARY) { summaryRequest(req.kind, start, end) }
        }
    }

    private fun summaryRequest(kind: SummaryKind, start: LocalDate, end: LocalDate) = buildJsonObject {
        put("kind", kind.wireName)
        put("start", start.toString())
        put("end", end.toString())
    }

    /**
     * 发起 AI 任务的固定流程（P13-14），在调用方的事务里、房间已锁：
     * - 同一个 jobId 再来一次：不是这个人、这个房间、这种任务 → 409；还没失败 → 返回现在的状态；失败了 → 重新排队
     * - 新任务：检查本月额度，写 ai_jobs，排进队列
     */
    private fun Tx.startJob(roomId: UUID, userId: UUID, jobId: UUID, jobKind: AiJobKind, queueKind: String, payload: () -> JsonObject): AiJobAccepted {
        val existing = AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull()
        if (existing != null) {
            if (existing[AiJobs.roomId] != roomId || existing[AiJobs.requestedBy] != userId || existing[AiJobs.kind] != jobKind.wireName) {
                throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            }
            val status = fromWire<AiJobStatus>(existing[AiJobs.status])
            if (status != AiJobStatus.Failed) return AiJobAccepted(jobId, status)
            checkQuota()
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[AiJobs.status] = AiJobStatus.Queued.wireName
                it[error] = null
                it[failReason] = null
                it[finishedAt] = null
                it[updatedAt] = clock.instant()
            }
        } else {
            checkQuota()
            insertJob(jobId, roomId, userId, jobKind, payload())
        }
        queue.enqueue(this, queueKind, buildJsonObject { put("aiJobId", jobId.toString()) }, maxAttempts = CHAT_ATTEMPTS)
        return AiJobAccepted(jobId, AiJobStatus.Queued)
    }

    private fun insertJob(jobId: UUID, roomId: UUID, userId: UUID?, jobKind: AiJobKind, payload: JsonObject) {
        val now = clock.instant()
        AiJobs.insert {
            it[id] = jobId
            it[AiJobs.roomId] = roomId
            it[requestedBy] = userId
            it[kind] = jobKind.wireName
            it[status] = AiJobStatus.Queued.wireName
            it[request] = payload
            it[inputTokens] = 0
            it[outputTokens] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private suspend fun summarize(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy]
        val req = row[AiJobs.request]
        val kind = fromWire<SummaryKind>(req["kind"]!!.jsonPrimitive.content)
        val start = LocalDate.parse(req["start"]!!.jsonPrimitive.content)
        val end = LocalDate.parse(req["end"]!!.jsonPrimitive.content)
        markRunning(jobId)
        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")

        val (names, lines) = db.tx(readOnly = true) {
            val names = RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName }
            names to SummaryData.gather(roomId, start, end, roomZone(roomId), names, roomPrefs(roomId))
        }
        val rangeText = "${start.year}年${start.monthValue}月${start.dayOfMonth}日—${end.year}年${end.monthValue}月${end.dayOfMonth}日"
        var body = "这段时间（$rangeText）没有记下什么。"
        var model: String? = null
        var tokensIn = 0
        var tokensOut = 0
        if (lines.isNotEmpty()) {
            val rendered = prompts.render("summary", mapOf(
                "range" to rangeText,
                "kind" to when (kind) { SummaryKind.Week -> "一周"; SummaryKind.Month -> "一个月"; SummaryKind.Year -> "一整年"; SummaryKind.Custom -> "一段时间" },
                "people" to names.values.joinToString("、"),
                "sources" to lines.joinToString("\n") { it.line },
                "length" to (if (kind == SummaryKind.Year) "1200" else "500"),
            ))
            val result = try {
                gateway.complete(
                    AiRequest(
                        rendered.system, listOf(AiMessage(AiMessage.Role.User, rendered.user)),
                        maxTokens = if (kind == SummaryKind.Year) YEAR_SUMMARY_MAX_TOKENS else SUMMARY_MAX_TOKENS, timeoutMillis = BACKGROUND_TIMEOUT_MS,
                    ),
                )
            } catch (e: AiProviderException) {
                log.warn("生成总结失败（第 {} 次）：{}", job.attempts, e.message)
                if (e.retryable && !job.isLastAttempt) throw e
                return fail(jobId, roomId, "没有得到总结", e.reason)
            }
            body = result.text.trim()
            model = result.model
            tokensIn = result.inputTokens
            tokensOut = result.outputTokens
        }
        val sources = SummaryData.cited(body, lines)
        db.tx {
            val now = clock.instant()
            val seq = writer.change(this, roomId, EntityType.Summary, jobId, askerId, now)
            Summaries.insert {
                it[id] = jobId
                it[Summaries.roomId] = roomId
                it[Summaries.seq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[Summaries.kind] = kind.wireName
                it[rangeStart] = start
                it[rangeEnd] = end
                it[Summaries.body] = body
                it[Summaries.sources] = sources
                it[aiDerived] = true
                it[locked] = kind == SummaryKind.Year
                it[requestedBy] = askerId
            }
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[AiJobs.model] = model
                it[inputTokens] = tokensIn
                it[outputTokens] = tokensOut
                it[resultRef] = "summary:$jobId"
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    /** 启动时调用：保证队列里有一个「年度检查」任务。 */
    suspend fun ensureYearlyCheck() = db.tx {
        val queued = Jobs.select(Jobs.id).where { (Jobs.kind eq JOB_YEARLY_CHECK) and (Jobs.status inList listOf("queued", "running")) }.any()
        if (!queued) queue.enqueue(this, JOB_YEARLY_CHECK, buildJsonObject { }, maxAttempts = 1)
    }

    /**
     * 年度检查（每 6 小时一次）：过了 1 月 1 日（按房间时区），上一年有内容、还没有年度回顾的房间，生成一份（锁定、不可删除）。
     * 同一个房间同一年的任务 id 固定，不会重复。
     */
    private suspend fun yearlyCheck() {
        try {
            if (gateway != null) {
                db.tx {
                    val roomIds = Rooms.select(Rooms.id, Rooms.createdAt).map { it[Rooms.id] to it[Rooms.createdAt] }
                    for ((roomId, createdAt) in roomIds) {
                        val zone = roomZone(roomId)
                        val year = clock.instant().atZone(zone).year - 1
                        val start = LocalDate.of(year, 1, 1)
                        val end = LocalDate.of(year, 12, 31)
                        if (!createdAt.isBefore(end.plusDays(1).atStartOfDay(zone).toInstant())) continue
                        val jobId = UUID.nameUUIDFromBytes("qichi:yearly:$roomId:$year".toByteArray())
                        if (AiJobs.select(AiJobs.id).where { AiJobs.id eq jobId }.any()) continue
                        val names = RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName }
                        if (SummaryData.gather(roomId, start, end, zone, names).isEmpty()) continue
                        insertJob(jobId, roomId, null, AiJobKind.YearlyReview, summaryRequest(SummaryKind.Year, start, end))
                        queue.enqueue(this, JOB_SUMMARY, buildJsonObject { put("aiJobId", jobId.toString()) }, maxAttempts = 3)
                    }
                }
            }
        } finally {
            db.tx { queue.enqueue(this, JOB_YEARLY_CHECK, buildJsonObject { }, maxAttempts = 1, delay = YEARLY_CHECK_EVERY) }
        }
    }

    private suspend fun suggestQuestion(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy] ?: return
        markRunning(jobId)
        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")
        val context = db.tx(readOnly = true) { chatContext(roomId) }
        val names = db.tx(readOnly = true) { RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName } }
        val rendered = prompts.render("question_suggest", mapOf(
            "now" to RoomContext.now(clock.instant(), db.tx(readOnly = true) { roomZone(roomId) }, names, null),
            "history" to context.joinToString("\n") { m -> "${m.authorId?.let(names::get) ?: "AI"}：${m.text}" }
                .ifEmpty { "（还没有聊天记录）" },
        ))
        val result = try {
            gateway.complete(AiRequest(rendered.system, listOf(AiMessage(AiMessage.Role.User, rendered.user)), maxTokens = QUESTION_MAX_TOKENS))
        } catch (e: AiProviderException) {
            log.warn("AI 出题失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            return fail(jobId, roomId, "没有得到题目", e.reason)
        }
        val question = result.text.lineSequence()
            .map { it.trim().removePrefix("问题：").trim().trim('"', '“', '”') }
            .firstOrNull { it.length in Limits.QUESTION_TEXT_LENGTH && (it.endsWith('？') || it.endsWith('?')) }
            ?: return fail(jobId, roomId, "没有得到题目")
        db.tx {
            val now = clock.instant()
            val questionId = UuidV7.generate()
            val seq = writer.change(this, roomId, EntityType.Question, questionId, askerId, now)
            Questions.insert {
                it[id] = questionId
                it[Questions.roomId] = roomId
                it[Questions.seq] = seq
                it[createdAt] = now
                it[updatedAt] = now
                it[text] = question
                it[questionSource] = QuestionSource.Ai.wireName
                it[createdBy] = null
                it[suggestedByJobId] = jobId
            }
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                it[resultRef] = "question:$questionId"
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    suspend fun job(userId: UUID, roomId: UUID, jobId: UUID): AiJob = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        AiJobs.selectAll().where { (AiJobs.id eq jobId) and (AiJobs.roomId eq roomId) }.singleOrNull()?.toAiJob(viewer = userId) ?: notFound()
    }

    // ── 写作助手（P9-04 / P9-05）：结果是一段文字，只给发起的人看，由人决定用不用 ──

    suspend fun requestWrite(userId: UUID, roomId: UUID, req: AiWriteRequest): AiJobAccepted {
        val text = req.text?.trim().orEmpty()
        validate {
            when (req.mode) {
                WriteAssistMode.Polish, WriteAssistMode.Proofread, WriteAssistMode.Shorten ->
                    check(text.length in 1..Limits.WRITE_ASSIST_TEXT_MAX, "text", "选中 1–${Limits.WRITE_ASSIST_TEXT_MAX} 字")
                WriteAssistMode.Titles -> check(text.isNotEmpty(), "text", "先写点内容再起标题")
                WriteAssistMode.Draft -> {
                    val start = req.rangeStart
                    val end = req.rangeEnd
                    check(req.genre != null, "genre", "选一个体裁")
                    check(start != null && end != null && !end.isBefore(start), "rangeStart", "时间范围不对")
                    if (start != null && end != null) {
                        check(ChronoUnit.DAYS.between(start, end) < Limits.WRITE_DRAFT_DAYS_MAX, "rangeEnd", "最多 ${Limits.WRITE_DRAFT_DAYS_MAX} 天")
                    }
                }
            }
        }
        return db.tx {
            rooms.requireMember(roomId, userId)
            if (gateway == null) throw unavailable()
            req.documentId?.let { docId ->
                val ok = Documents.select(Documents.id).where { (Documents.id eq docId) and (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.any()
                if (!ok) notFound()
            }
            RoomRepository.lockRoom(roomId)
            startJob(roomId, userId, req.jobId, AiJobKind.WriteAssist, JOB_WRITE) {
                QichiJson.encodeToJsonElement(AiWriteRequest.serializer(), req.copy(text = text.take(Limits.WRITE_TITLES_TEXT_MAX))).jsonObject
            }
        }
    }

    private suspend fun writeAssist(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy]
        val req = QichiJson.decodeFromJsonElement(AiWriteRequest.serializer(), row[AiJobs.request])
        markRunning(jobId)
        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")

        // 起草时能自己查房间资料（P14-04），和问 AI 一样守「AI 能看什么」；润色、改错字这类只看选中的文字，不用查
        var tools: RoomTools? = null
        val (name, vars) = db.tx(readOnly = true) {
            val title = req.documentId?.let { d -> Documents.select(Documents.title).where { Documents.id eq d }.singleOrNull()?.get(Documents.title) }
            when (req.mode) {
                WriteAssistMode.Polish -> "write_polish" to mapOf("title" to (title ?: "（没有标题）"), "text" to req.text.orEmpty())
                WriteAssistMode.Proofread -> "write_proofread" to mapOf("text" to req.text.orEmpty())
                WriteAssistMode.Shorten -> "write_shorten" to mapOf("text" to req.text.orEmpty())
                WriteAssistMode.Titles -> "write_titles" to mapOf("title" to (title ?: "（没有标题）"), "text" to req.text.orEmpty())
                WriteAssistMode.Draft -> {
                    val names = RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName }
                    val zone = roomZone(roomId)
                    val start = req.rangeStart!!
                    val end = req.rangeEnd!!
                    val lines = SummaryData.gather(roomId, start, end, zone, names, roomPrefs(roomId))
                    tools = if (config.tools) RoomTools(roomId, clock.instant(), zone, names, roomPrefs(roomId), SourceBook(), openReaders(roomId))
                        .takeIf { it.definitions.isNotEmpty() } else null
                    "write_draft" to mapOf(
                        "now" to RoomContext.now(clock.instant(), zone, names, askerId),
                        "genre" to when (req.genre!!) {
                            DraftGenre.Travel -> "游记：按时间顺序写这趟出行，去了哪里、做了什么、有什么感受"
                            DraftGenre.Letter -> "写给对方的一封信：以提问的人的口吻，写给另一个人"
                            DraftGenre.Review -> "回顾：这段时间一起做了什么、定下了什么、心情怎么样"
                        },
                        "range" to "${start.monthValue}月${start.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日",
                        "sources" to lines.joinToString("\n") { it.line }.ifEmpty { "（这段时间没有记下什么）" },
                        "lookup" to if (tools != null) prompts.render("write_lookup", mapOf("rounds" to TOOL_ROUNDS.toString())).user + "\n" else "",
                    )
                }
            }
        }
        val rendered = prompts.render(name, vars)
        val result = try {
            completeLookingUp(
                gateway, rendered.system, rendered.user, writeMaxTokens(req), tools, "写作助手", timeoutMillis = BACKGROUND_TIMEOUT_MS,
            )
        } catch (e: AiProviderException) {
            log.warn("写作助手失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            return fail(jobId, roomId, "没有得到结果", e.reason)
        }
        // 润色、改错字、缩短没写完就不给：点「用这个」会把选中的整段换成半段
        if (result.truncated && req.mode in REWRITE_MODES) {
            log.warn("写作助手（{}）写到长度上限被截断，不给半截的结果", req.mode.wireName)
            return fail(jobId, roomId, "结果太长没写完")
        }
        val text = result.text.replace(CITATION, "").trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return fail(jobId, roomId, "没有得到结果")
        db.tx {
            val now = clock.instant()
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Done.wireName
                it[model] = result.model
                it[inputTokens] = result.inputTokens
                it[outputTokens] = result.outputTokens
                it[resultText] = text
                it[resultRef] = "text"
                it[error] = null
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    /**
     * 不流式的「边查边答」（P14-04：阅读里请 AI、写作助手起草也能自己查房间资料）：模型要查就执行 [tools]、把结果交回去，
     * 直到它直接回答；最多 [TOOL_ROUNDS] 轮，轮数或时间用完时不许再查（和问 AI 一样，见 answerInChat）。
     * 没有 [tools] 就是一次普通的请求。返回最后一轮的结果，用量是几轮加起来的；最后还是只想查、一个字没写时文字是空的。
     */
    private suspend fun completeLookingUp(
        gateway: AiGateway, system: String, user: String, maxTokens: Int, tools: RoomTools?, what: String,
        timeoutMillis: Long = CHAT_TIMEOUT_MS,
    ): AiResult {
        if (tools == null) return gateway.complete(AiRequest(system, listOf(AiMessage(AiMessage.Role.User, user)), maxTokens = maxTokens, timeoutMillis = timeoutMillis))
        val messages = mutableListOf(AiMessage(AiMessage.Role.User, user))
        var tokensIn = 0
        var tokensOut = 0
        val deadline = System.currentTimeMillis() + timeoutMillis
        for (round in 0..TOOL_ROUNDS) {
            val remaining = deadline - System.currentTimeMillis()
            val last = round == TOOL_ROUNDS || remaining < LAST_ROUND_MS
            if (last && round > 0) messages += AiMessage(AiMessage.Role.User, prompts.render("chat_lookup_done", emptyMap()).user)
            val r = gateway.complete(
                AiRequest(system, messages.toList(), maxTokens = maxTokens, timeoutMillis = remaining.coerceAtLeast(MIN_ROUND_MS), tools = tools.definitions),
            )
            tokensIn += r.inputTokens
            tokensOut += r.outputTokens
            if (r.toolCalls.isEmpty() || last) return r.copy(text = if (r.toolCalls.isEmpty()) r.text else "", inputTokens = tokensIn, outputTokens = tokensOut)
            messages += AiMessage(AiMessage.Role.Assistant, r.text, toolCalls = r.toolCalls)
            // 每次查询各用一个事务：一次出错不影响别的，把「没查成」告诉模型让它继续
            val outputs = r.toolCalls.map { c ->
                try {
                    db.tx(readOnly = true) { tools.run(c) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("{}的查询 {} 出错", what, c.name, e)
                    "这次没查成，换个条件再试，或者先用已有的资料回答。"
                }
            }
            r.toolCalls.zip(outputs).forEach { (c, out) -> messages += AiMessage(AiMessage.Role.Tool, out, toolCallId = c.id) }
            log.info("{}第 {} 轮查了：{}", what, round + 1, r.toolCalls.joinToString { it.name })
        }
        error("最后一轮总会返回")
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
    /**
     * 停下正在回答的 AI（P10-04）。还在排队：直接写一条正文为空的已停下消息；正在生成：取消调用，
     * 由 [answerInChat] 把已经写出来的部分存成消息。已经结束的原样返回。
     */
    suspend fun stop(userId: UUID, roomId: UUID, jobId: UUID): AiJobAccepted {
        var stoppedWhileQueued = false
        val status = db.tx {
            rooms.requireMember(roomId, userId)
            val row = AiJobs.selectAll().where { (AiJobs.id eq jobId) and (AiJobs.roomId eq roomId) }.forUpdate().singleOrNull()
                ?.takeIf { it[AiJobs.kind] == AiJobKind.ChatAnswer.wireName } ?: throw notFound()
            if (row[AiJobs.requestedBy] != userId) throw ApiException(ProblemCode.Forbidden, "只有提问的人能停下")
            when (val status = fromWire<AiJobStatus>(row[AiJobs.status])) {
                AiJobStatus.Queued -> {
                    val prompt = row[AiJobs.request]["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    insertAnswer(jobId, roomId, userId, prompt, "", emptyList(), emptyList(), stopped = true)
                    finishJob(jobId, model = null, inputTokens = 0, outputTokens = 0)
                    stoppedWhileQueued = true
                    AiJobStatus.Done
                }
                AiJobStatus.Running -> {
                    stopRequested += jobId
                    status
                }
                else -> status
            }
        }
        if (stoppedWhileQueued) realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
        streaming[jobId]?.cancel()
        return AiJobAccepted(jobId, status)
    }

    /** 写问 AI 的回答消息（id = jobId）和它带的动作提议。在事务里调用。 */
    private fun Tx.insertAnswer(
        jobId: UUID, roomId: UUID, askerId: UUID?, prompt: String, body: String,
        sources: List<app.qichi.shared.api.SummarySource>, actions: List<ParsedAction>, stopped: Boolean,
    ) {
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
            it[Messages.body] = body.take(MESSAGE_MAX)
            it[aiPrompt] = prompt
            it[aiSources] = sources
            it[aiStopped] = stopped
            it[aiAskedBy] = askerId
        }
        actions.forEachIndexed { i, action ->
            val actionId = UuidV7.generate()
            val actionSeq = writer.change(this, roomId, EntityType.AiAction, actionId, askerId, now)
            AiActions.insert {
                it[AiActions.id] = actionId
                it[AiActions.roomId] = roomId
                it[AiActions.seq] = actionSeq
                it[createdAt] = now
                it[updatedAt] = now
                it[messageId] = jobId
                it[position] = i
                it[AiActions.kind] = action.kind.wireName
                it[draft] = action.draft
                it[AiActions.status] = AiActionStatus.Proposed.wireName
                it[requestedBy] = askerId
            }
        }
    }

    private fun Tx.finishJob(jobId: UUID, model: String?, inputTokens: Int, outputTokens: Int) {
        val now = clock.instant()
        AiJobs.update({ AiJobs.id eq jobId }) {
            it[status] = AiJobStatus.Done.wireName
            it[AiJobs.model] = model
            it[AiJobs.inputTokens] = inputTokens
            it[AiJobs.outputTokens] = outputTokens
            it[resultRef] = "message:$jobId"
            it[error] = null
            it[finishedAt] = now
            it[updatedAt] = now
        }
    }

    private suspend fun answerInChat(job: QueuedJob) {
        val jobId = UUID.fromString(job.payload["aiJobId"]!!.jsonPrimitive.content)
        val row = db.tx { AiJobs.selectAll().where { AiJobs.id eq jobId }.singleOrNull() } ?: return
        if (row[AiJobs.status] == AiJobStatus.Done.wireName) return
        val roomId = row[AiJobs.roomId]
        val askerId = row[AiJobs.requestedBy]
        val prompt = row[AiJobs.request]["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceId = row[AiJobs.request]["sourceMessageId"]?.jsonPrimitive?.contentOrNull?.let(UUID::fromString)
        // 排队时已经被停下（stop 写好了消息、把任务标成 done）就不再回答
        if (!markChatRunning(jobId)) return

        val gateway = gateway ?: return fail(jobId, roomId, "AI 服务没有开启")
        val now = clock.instant()
        val input = db.tx(readOnly = true) {
            val context = chatContext(roomId)
            val names = RoomRepository.activeMembers(roomId).associate { it.userId to it.displayName }
            val zone = roomZone(roomId)
            val focus = sourceId?.let { id ->
                messageQuery().where { (Messages.id eq id) and Visibility.quotableMessage() }.singleOrNull()?.toMessage()
            }
            val query = listOfNotNull(prompt, focus?.body).joinToString(" ")
            val prefs = roomPrefs(roomId)
            // AI 能自己查时只备常备的一小份（P11）
            val sources = RoomContext.gather(roomId, query, now, zone, names, prefs, context.map { it.id }.toSet(), standingOnly = config.tools)
            val plans = Plans.select(Plans.id, Plans.title)
                .where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() and (Plans.status eq PlanStatus.Active.wireName) }
                .associate { it[Plans.title] to it[Plans.id] }
            ChatInputs(context, names, sources, zone, focus, plans, prefs, openReaders(roomId))
        }
        val (context, names, sources, zone) = input
        // 事先备料和工具查到的共用一套编号
        val book = SourceBook(sources)
        val tools = if (config.tools) RoomTools(roomId, now, zone, names, input.prefs, book, input.openReaders).takeIf { it.definitions.isNotEmpty() } else null
        val focusText = input.focus?.let { m ->
            val t = m.createdAt.atZone(zone)
            "要整理的消息（${m.authorId?.let(names::get) ?: "AI"}，${t.monthValue}月${t.dayOfMonth}日 %02d:%02d）：${m.body.take(CONTEXT_LINE_MAX * 3)}\n".format(t.hour, t.minute) +
                "请把它整理成可以记下来的日程、待办、档案或灵感（用 <actions>），文字只要一两句说明整理了什么。\n\n"
        }.orEmpty()
        val rendered = prompts.render(
            "chat_answer",
            mapOf(
                "now" to RoomContext.now(now, zone, names, askerId),
                "sources" to sources.joinToString("\n") { it.line }.ifEmpty { "（没有找到相关的）" },
                "asker" to (names[askerId] ?: "提问的人"),
                "history" to context.joinToString("\n") { m -> "${m.authorId?.let(names::get) ?: "AI"}：${m.text}" }.ifEmpty { "（还没有聊天记录）" },
                "focus" to focusText,
                "prompt" to prompt,
                "lookup" to if (tools != null) prompts.render("chat_lookup", mapOf("rounds" to TOOL_ROUNDS.toString())).user + "\n" else "",
            ),
        )
        // 边生成边推给 App（P8-03）：最多每 [STREAM_EVERY_MS] 推一次到目前为止给人看的部分
        var lastSent = 0L
        var lastText = ""
        // 这一轮到目前为止的回答（查资料前说的话不算进最后的回答）
        var soFar = ""
        val messages = mutableListOf(AiMessage(AiMessage.Role.User, rendered.user))
        var tokensIn = 0
        var tokensOut = 0
        val deadline = System.currentTimeMillis() + CHAT_TIMEOUT_MS

        /** 一轮一轮问：模型要查就执行工具、把结果交回去，直到它直接回答；轮数或时间用完时不许再查（P11）。 */
        suspend fun converse(): AiResult {
            for (round in 0..TOOL_ROUNDS) {
                val remaining = deadline - System.currentTimeMillis()
                val last = tools == null || round == TOOL_ROUNDS || remaining < LAST_ROUND_MS
                if (tools != null && last && round > 0) messages += AiMessage(AiMessage.Role.User, prompts.render("chat_lookup_done", emptyMap()).user)
                soFar = ""
                lastText = ""
                val request = AiRequest(
                    rendered.system, messages.toList(), maxTokens = CHAT_MAX_TOKENS,
                    timeoutMillis = remaining.coerceAtLeast(MIN_ROUND_MS), tools = tools?.definitions.orEmpty(),
                )
                val r = gateway.stream(request) { text ->
                    soFar = text
                    val visible = AiActionParser.visiblePart(text)
                    val t = System.currentTimeMillis()
                    if (visible.isNotEmpty() && visible != lastText && t - lastSent >= STREAM_EVERY_MS) {
                        lastSent = t
                        lastText = visible
                        realtime.aiDelta(roomId, jobId, visible)
                    }
                }
                tokensIn += r.inputTokens
                tokensOut += r.outputTokens
                if (tools == null || r.toolCalls.isEmpty() || last) return r
                messages += AiMessage(AiMessage.Role.Assistant, r.text, toolCalls = r.toolCalls)
                realtime.aiDelta(roomId, jobId, "", "正在查：" + r.toolCalls.map(tools::status).distinct().joinToString("、"))
                // 每次查询各用一个事务：一次出错不影响别的，把「没查成」告诉模型让它继续
                val outputs = r.toolCalls.map { c ->
                    try {
                        db.tx(readOnly = true) { tools.run(c) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("问 AI 的查询 {} 出错", c.name, e)
                        "这次没查成，换个条件再试，或者先用已有的资料回答。"
                    }
                }
                r.toolCalls.zip(outputs).forEach { (c, out) -> messages += AiMessage(AiMessage.Role.Tool, out, toolCallId = c.id) }
                log.info("问 AI 第 {} 轮查了：{}", round + 1, r.toolCalls.joinToString { it.name })
            }
            error("最后一轮总会返回")
        }

        val result = try {
            coroutineScope {
                val call = async { converse() }
                streaming[jobId] = call
                // stop 可能在登记之前就来了
                if (jobId in stopRequested) call.cancel()
                try {
                    call.await()
                } catch (e: CancellationException) {
                    ensureActive()
                    if (jobId !in stopRequested) throw e
                    null
                } finally {
                    streaming.remove(jobId)
                }
            }
        } catch (e: AiProviderException) {
            log.warn("问 AI 失败（第 {} 次）：{}", job.attempts, e.message)
            if (e.retryable && !job.isLastAttempt) throw e
            stopRequested -= jobId
            return fail(jobId, roomId, "没有得到回答", e.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 意外的错误：交给任务队列重试；最后一次也失败时标成失败，App 上显示「重试」而不是一直转圈
            if (!job.isLastAttempt) throw e
            log.warn("问 AI 出错，不再重试", e)
            stopRequested -= jobId
            return fail(jobId, roomId, "没有得到回答")
        }

        if (result == null) {
            // 停下了：存已经写出来的部分（去掉没写完的动作段和半个引用编号），不带动作提议。
            // 用量：已完成的几轮按服务商报的，正在进行的这一轮按字数估。
            val written = soFar.substringBefore("<act").replace(Regex("""\[\d*$"""), "").trimEnd()
            val answer = RoomContext.renumber(written, book.all)
            val pendingIn = (rendered.system.length + messages.sumOf { it.content.length }) / 2
            db.tx {
                insertAnswer(jobId, roomId, askerId, prompt, answer.body, answer.sources, emptyList(), stopped = true)
                finishJob(jobId, gateway.model, tokensIn + pendingIn, tokensOut + soFar.length)
            }
            stopRequested -= jobId
            realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
            return
        }
        stopRequested -= jobId

        // 最后一轮还是只想查、一个字没写：告诉他们没整理出来
        val text = result.text.ifBlank { if (result.toolCalls.isNotEmpty()) GAVE_UP else result.text }
        val parsed = AiActionParser(zone, names.entries.associate { (id, name) -> name to id }, input.plans).parse(text)
        val shown = parsed.text.ifBlank { if (parsed.actions.isEmpty()) text else "可以记下这些：" }
        val answer = RoomContext.renumber(if (result.truncated && result.text.isNotBlank()) shown + CUT_OFF else shown, book.all)
        db.tx {
            insertAnswer(jobId, roomId, askerId, prompt, answer.body, answer.sources, parsed.actions, stopped = false)
            finishJob(jobId, result.model, tokensIn, tokensOut)
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Done.wireName)
    }

    private data class ContextLine(val id: UUID, val authorId: UUID?, val text: String)

    private data class ChatInputs(
        val context: List<ContextLine>,
        val names: Map<UUID, String>,
        val sources: List<SourceLine>,
        val zone: ZoneId,
        /** 「让 AI 整理」的那条消息 */
        val focus: app.qichi.shared.api.Message?,
        /** 进行中的计划：标题 → id（AI 提议「加进某个计划」时用） */
        val plans: Map<String, UUID>,
        /** 两个人都允许 AI 看的类别 */
        val prefs: AiPrefs,
        /** 打开了「我没公开的阅读记录」的人（P14-02） */
        val openReaders: Set<UUID>,
    )

    /** 某人自己的「AI 能看什么」设置。 */
    private fun prefsOf(userId: UUID): AiPrefs =
        AiPrefs.from(Users.select(Users.aiPrefs).where { Users.id eq userId }.singleOrNull()?.get(Users.aiPrefs))

    /** 房间里两个人都允许的类别（P11：谁发起的 AI 请求都按这个）。 */
    private fun roomPrefs(roomId: UUID): AiPrefs =
        RoomRepository.activeMembers(roomId).map { prefsOf(it.userId) }.fold(AiPrefs()) { a, b -> a and b }

    /** 打开了「我没公开的阅读记录」的人（P14-02：各管各的，不取交集；「阅读」这一类有人关掉时用不上）。 */
    private fun openReaders(roomId: UUID): Set<UUID> =
        RoomRepository.activeMembers(roomId).filter { prefsOf(it.userId).readingPrivate }.map { it.userId }.toSet()

    /** 最近 [CONTEXT_MESSAGES] 条没撤回、没删除的消息，从早到晚。 */
    private fun chatContext(roomId: UUID): List<ContextLine> =
        messageQuery()
            .where { (Messages.roomId eq roomId) and Visibility.quotableMessage() }
            .orderBy(Messages.createdSeq, SortOrder.DESC)
            .limit(CONTEXT_MESSAGES)
            .map { it.toMessage() }
            .reversed()
            .mapNotNull { m ->
                // 每条最多 [CONTEXT_LINE_MAX] 字；以前借用回复摘要的规则，每条只剩 60 字（Q18）
                val text = if (m.kind == MessageKind.Ai) m.body.take(CONTEXT_LINE_MAX) else MessageRules.asLine(m.kind, m.body, m.file?.fileName, CONTEXT_LINE_MAX)
                text?.takeIf { it.isNotBlank() }?.let { ContextLine(m.id, m.authorId, it) }
            }

    /** 把问 AI 标成进行中；任务已经结束（排队时被停下）时返回 false。 */
    private suspend fun markChatRunning(jobId: UUID): Boolean = db.tx {
        AiJobs.update({ (AiJobs.id eq jobId) and (AiJobs.status neq AiJobStatus.Done.wireName) }) {
            it[status] = AiJobStatus.Running.wireName
            it[updatedAt] = clock.instant()
        } > 0
    }

    private suspend fun markRunning(jobId: UUID) = db.tx {
        AiJobs.update({ AiJobs.id eq jobId }) {
            it[status] = AiJobStatus.Running.wireName
            it[updatedAt] = clock.instant()
        }
    }

    /** 标成失败并通知；[kind] 是失败的种类（P16-08），App 据此说清原因、决定给不给「重试」。 */
    private suspend fun fail(jobId: UUID, roomId: UUID, reason: String, kind: AiFailReason = AiFailReason.Other) {
        db.tx {
            val now = clock.instant()
            AiJobs.update({ AiJobs.id eq jobId }) {
                it[status] = AiJobStatus.Failed.wireName
                it[error] = reason
                it[failReason] = kind.wireName
                it[finishedAt] = now
                it[updatedAt] = now
            }
        }
        realtime.aiDone(roomId, jobId, AiJobStatus.Failed.wireName, kind.wireName)
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

    /** [viewer] 是发起人时才带上写作助手的结果文字。 */
    private fun ResultRow.toAiJob(viewer: UUID? = null) = AiJob(
        id = this[AiJobs.id],
        roomId = this[AiJobs.roomId],
        kind = fromWire(this[AiJobs.kind]),
        status = fromWire(this[AiJobs.status]),
        model = this[AiJobs.model],
        inputTokens = this[AiJobs.inputTokens],
        outputTokens = this[AiJobs.outputTokens],
        resultRef = this[AiJobs.resultRef],
        error = this[AiJobs.error],
        failReason = this[AiJobs.failReason],
        createdAt = this[AiJobs.createdAt],
        finishedAt = this[AiJobs.finishedAt],
        resultText = this[AiJobs.resultText]?.takeIf { viewer != null && this[AiJobs.requestedBy] == viewer },
    )

    companion object {
        const val JOB_CHAT = "ai.chat"
        const val JOB_QUESTION = "ai.question_suggest"
        const val JOB_READ = "ai.read_explain"
        const val JOB_SUMMARY = "ai.summary"
        const val JOB_YEARLY_CHECK = "ai.yearly_check"
        const val JOB_REVIEW = "ai.review_findings"
        const val JOB_WRITE = "ai.write_assist"
        /*
         * 回答长度上限（token）。只是上限，用多少算多少；gpt-6-sol 这类「先想再答」的模型想的部分也算在里面，
         * 以前给得太紧（出题 120、问 AI 800、改错字 1500），长一点的回答会被截断，想得久了甚至一个字都没有。
         */
        private const val WRITE_MAX_TOKENS = 2_000
        private const val DRAFT_MAX_TOKENS = 5_000
        private const val REVIEW_MAX_TOKENS = 8_000
        private const val SUMMARY_MAX_TOKENS = 2_500
        private const val YEAR_SUMMARY_MAX_TOKENS = 5_000
        private const val QUESTION_MAX_TOKENS = 800

        /** 总结、审稿、写作助手这类后台活最多等 5 分钟（输出长，不像问 AI 有人盯着） */
        private const val BACKGROUND_TIMEOUT_MS = 300_000L

        /** 改写选中文字的几种：结果和原文差不多长 */
        private val REWRITE_MODES = setOf(WriteAssistMode.Polish, WriteAssistMode.Proofread, WriteAssistMode.Shorten)

        /** 写到上限被截断时接在后面，让人知道没写完 */
        const val CUT_OFF = "……（写得太长，没写完）"

        /** 改写选中的文字时按原文长短给上限：一个汉字大约一到两个 token，再留出想的余地 */
        internal fun writeMaxTokens(req: AiWriteRequest): Int = when (req.mode) {
            WriteAssistMode.Draft -> DRAFT_MAX_TOKENS
            in REWRITE_MODES -> maxOf(WRITE_MAX_TOKENS, req.text.orEmpty().length * 2 + 1_000)
            else -> WRITE_MAX_TOKENS
        }
        private val YEARLY_CHECK_EVERY: java.time.Duration = java.time.Duration.ofHours(6)
        private const val READ_TEXT_MAX = 2000
        private const val READ_CONTEXT_MAX = 500
        private const val COMPARE_NOTES = 20
        const val PROMPT_MAX = 2000
        private const val CHAT_ATTEMPTS = 2
        private const val CHAT_MAX_TOKENS = 2_000

        /** 问 AI 最多等 3 分钟（边生成边显示，等的时候看得到进度） */
        private const val CHAT_TIMEOUT_MS = 180_000L
        private const val STREAM_EVERY_MS = 250L

        /** 问 AI 最多查几轮资料（P11） */
        const val TOOL_ROUNDS = 6

        /** 剩下不到这么多时间就不再查，直接作答 */
        private const val LAST_ROUND_MS = 40_000L

        /** 每一轮至少给这么多时间 */
        private const val MIN_ROUND_MS = 15_000L

        const val GAVE_UP = "资料查了不少，还是没能整理出回答。换个具体点的问法再试试？"

        /** 回答里的来源编号 [n]：阅读、写作的结果点不开来源，去掉（P14-04） */
        private val CITATION = Regex("""\s?\[\d{1,4}]""")
        private const val CONTEXT_MESSAGES = 30
        private const val CONTEXT_LINE_MAX = 300
        private const val MESSAGE_MAX = 10_000
    }
}
