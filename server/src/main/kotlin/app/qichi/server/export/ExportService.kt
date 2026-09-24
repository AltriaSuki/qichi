package app.qichi.server.export

import app.qichi.server.archive.toArchiveItem
import app.qichi.server.board.toBoardPost
import app.qichi.server.board.toBoardTopic
import app.qichi.server.db.Answers
import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.BoardPosts
import app.qichi.server.db.BoardTopics
import app.qichi.server.db.Books
import app.qichi.server.db.Decisions
import app.qichi.server.db.DocumentVersions
import app.qichi.server.db.Documents
import app.qichi.shared.rules.DocumentImages
import app.qichi.server.db.Events
import app.qichi.server.db.Files
import app.qichi.server.db.Highlights
import app.qichi.server.db.Ideas
import app.qichi.server.db.Messages
import app.qichi.server.db.Milestones
import app.qichi.server.db.Moods
import app.qichi.server.db.PlanLogs
import app.qichi.server.db.PlanStages
import app.qichi.server.db.Plans
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.QnaRounds
import app.qichi.server.db.Questions
import app.qichi.server.db.ReadingProgressTable
import app.qichi.server.db.Rooms
import app.qichi.server.db.Summaries
import app.qichi.server.db.AnnotationReplies
import app.qichi.server.db.AiFindings
import app.qichi.server.review.toAiFinding
import app.qichi.server.db.Annotations
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.ReviewVersions
import app.qichi.server.review.reviewVersionQuery
import app.qichi.server.review.toAnnotation
import app.qichi.server.review.toAnnotationReply
import app.qichi.server.review.toReviewDocument
import app.qichi.server.review.toReviewVersion
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.decisions.toDecision
import app.qichi.server.documents.toDocument
import app.qichi.server.events.toEvent
import app.qichi.server.files.FileStorage
import app.qichi.server.ideas.toIdea
import app.qichi.server.messages.messageQuery
import app.qichi.server.messages.toMessage
import app.qichi.server.moods.toMood
import app.qichi.server.plans.toMilestone
import app.qichi.server.plans.toPlan
import app.qichi.server.plans.toPlanLog
import app.qichi.server.plans.toPlanStage
import app.qichi.server.qna.toAnswer
import app.qichi.server.qna.toQnaRound
import app.qichi.server.qna.toQuestion
import app.qichi.server.reading.bookQuery
import app.qichi.server.reading.toBook
import app.qichi.server.reading.toHighlight
import app.qichi.server.reading.toReadingProgress
import app.qichi.server.reading.visibleTo
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.server.summaries.toSummary
import app.qichi.server.todos.toTodo
import app.qichi.shared.api.QichiJson
import app.qichi.shared.model.MessageKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.OutputStream
import java.sql.Connection
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出包里要写进去的东西（在一个一致性快照里取出来，之后再慢慢写 ZIP）。 */
class ExportBundle(
    val roomName: String,
    val json: JsonElement,
    val chatMarkdown: String,
    /** 文件名 → 文稿最新版本的正文 */
    val documents: List<Pair<String, String>>,
    /** 附件：ZIP 里的名字 → 存储路径 */
    val files: List<Pair<String, String>>,
)

/**
 * 房间数据导出（P7-05）：一个 ZIP——data.json（全部内容）、聊天记录.md、文稿/、说明.txt，可选 附件/。
 * 不含撤回的内容、回收站里的内容、对方没共享的书中标记（docs/05-sync-offline.md）。
 */
class ExportService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val storage: FileStorage,
    private val clock: Clock,
) {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    suspend fun bundle(userId: UUID, roomId: UUID, includeFiles: Boolean): ExportBundle =
        db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            rooms.requireMember(roomId, userId)
            val room = RoomRepository.room(roomId)!!
            val members = RoomRepository.allMembers(roomId)
            val names = members.associate { it.userId to it.displayName }
            val zone = runCatching { ZoneId.of(room.timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))

            val messages = messageQuery().where { (Messages.roomId eq roomId) and Messages.deletedAt.isNull() and Messages.retractedAt.isNull() }
                .orderBy(Messages.createdSeq, SortOrder.ASC).map { it.toMessage() }
            val revealed = QnaRounds.select(QnaRounds.id, QnaRounds.revealedAt).where { QnaRounds.roomId eq roomId }
                .associate { it[QnaRounds.id] to (it[QnaRounds.revealedAt] != null) }
            val documents = Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.map { it.toDocument() }
            val latestBodies = documents.filter { it.latestVersion > 0 }.associate { d ->
                d.id to DocumentVersions.select(DocumentVersions.body)
                    .where { (DocumentVersions.documentId eq d.id) and (DocumentVersions.version eq d.latestVersion) }.single()[DocumentVersions.body]
            }

            fun <T> enc(list: List<T>, serializer: kotlinx.serialization.KSerializer<T>) = QichiJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(serializer), list)
            // 审稿：不含回收站里的审稿文件和它下面的一切
            val reviewDocs = ReviewDocuments.selectAll().where { (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }.map { it.toReviewDocument() }
            val liveDocs = reviewDocs.map { it.id }.toSet()
            val reviewVersions = reviewVersionQuery().where { ReviewVersions.roomId eq roomId }.map { it.toReviewVersion() }.filter { it.documentId in liveDocs }
            val annotations = Annotations.selectAll().where { (Annotations.roomId eq roomId) and Annotations.deletedAt.isNull() }
                .map { it.toAnnotation() }.filter { it.documentId in liveDocs }
            val json = buildJsonObject {
                put("format", "qichi-export-1")
                put("exportedAt", clock.instant().toString())
                put("room", QichiJson.encodeToJsonElement(room))
                put("members", QichiJson.encodeToJsonElement(members))
                put("messages", QichiJson.encodeToJsonElement(messages))
                put("moods", QichiJson.encodeToJsonElement(Moods.selectAll().where { (Moods.roomId eq roomId) and Moods.deletedAt.isNull() }.map { it.toMood() }))
                put("todos", QichiJson.encodeToJsonElement(Todos.selectAll().where { (Todos.roomId eq roomId) and Todos.deletedAt.isNull() }.map { it.toTodo() }))
                put("events", QichiJson.encodeToJsonElement(Events.selectAll().where { (Events.roomId eq roomId) and Events.deletedAt.isNull() }.map { it.toEvent() }))
                put("questions", QichiJson.encodeToJsonElement(Questions.selectAll().where { (Questions.roomId eq roomId) and Questions.deletedAt.isNull() }.map { it.toQuestion() }))
                put("qnaRounds", QichiJson.encodeToJsonElement(QnaRounds.selectAll().where { QnaRounds.roomId eq roomId }.map { it.toQnaRound() }))
                // 还没揭晓的回答只导出自己的
                put("answers", QichiJson.encodeToJsonElement(Answers.selectAll().where { (Answers.roomId eq roomId) and Answers.deletedAt.isNull() }.map { it.toAnswer() }
                    .filter { it.authorId == userId || revealed[it.roundId] == true }))
                put("plans", QichiJson.encodeToJsonElement(Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.map { it.toPlan() }))
                put("planStages", QichiJson.encodeToJsonElement(PlanStages.selectAll().where { (PlanStages.roomId eq roomId) and PlanStages.deletedAt.isNull() }.map { it.toPlanStage() }))
                put("milestones", QichiJson.encodeToJsonElement(Milestones.selectAll().where { (Milestones.roomId eq roomId) and Milestones.deletedAt.isNull() }.map { it.toMilestone() }))
                put("planLogs", QichiJson.encodeToJsonElement(PlanLogs.selectAll().where { (PlanLogs.roomId eq roomId) and PlanLogs.deletedAt.isNull() }.map { it.toPlanLog() }))
                put("ideas", QichiJson.encodeToJsonElement(Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.map { it.toIdea() }))
                put("documents", QichiJson.encodeToJsonElement(documents))
                put("boardTopics", QichiJson.encodeToJsonElement(BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.map { it.toBoardTopic() }))
                put("boardPosts", QichiJson.encodeToJsonElement(BoardPosts.selectAll().where { (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() }.map { it.toBoardPost() }))
                put("archiveItems", QichiJson.encodeToJsonElement(ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }.map { it.toArchiveItem() }))
                put("decisions", QichiJson.encodeToJsonElement(Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }.map { it.toDecision() }))
                put("books", QichiJson.encodeToJsonElement(bookQuery().where { (Books.roomId eq roomId) and Books.deletedAt.isNull() }.map { it.toBook() }))
                put("readingProgress", QichiJson.encodeToJsonElement(ReadingProgressTable.selectAll().where { ReadingProgressTable.roomId eq roomId }.map { it.toReadingProgress() }))
                put("highlights", QichiJson.encodeToJsonElement(Highlights.selectAll().where { (Highlights.roomId eq roomId) and Highlights.deletedAt.isNull() }
                    .map { it.toHighlight() }.filter { it.visibleTo(userId) }))
                put("summaries", QichiJson.encodeToJsonElement(Summaries.selectAll().where { (Summaries.roomId eq roomId) and Summaries.deletedAt.isNull() }.map { it.toSummary() }))
                put("reviewDocuments", QichiJson.encodeToJsonElement(reviewDocs))
                put("reviewVersions", QichiJson.encodeToJsonElement(reviewVersions))
                put("annotations", QichiJson.encodeToJsonElement(annotations))
                put("aiFindings", QichiJson.encodeToJsonElement(AiFindings.selectAll().where { AiFindings.roomId eq roomId }
                    .map { it.toAiFinding() }.filter { it.documentId in liveDocs }))
                put("annotationReplies", QichiJson.encodeToJsonElement(AnnotationReplies.selectAll().where { (AnnotationReplies.roomId eq roomId) and AnnotationReplies.deletedAt.isNull() }
                    .map { it.toAnnotationReply() }.filter { it.annotationId in annotations.map { a -> a.id }.toSet() }))
            }

            val chat = buildString {
                appendLine("# ${room.name} · 聊天记录")
                appendLine()
                messages.forEach { m ->
                    val who = when (m.kind) {
                        MessageKind.Ai -> "AI"
                        MessageKind.System -> "系统"
                        else -> names[m.authorId] ?: "其中一人"
                    }
                    val text = buildString {
                        if (m.file != null) append(if (m.kind == MessageKind.Image) "[图片 ${m.file!!.fileName}] " else "[文件 ${m.file!!.fileName}] ")
                        append(m.body)
                    }.trim()
                    appendLine("**${m.createdAt.atZone(zone).format(time)} $who**：$text")
                    appendLine()
                }
            }

            val usedNames = mutableSetOf<String>()
            fun unique(base: String, ext: String): String {
                val clean = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "未命名" }
                var name = "$clean$ext"
                var i = 2
                while (!usedNames.add(name)) name = "$clean ($i)$ext".also { i++ }
                return name
            }
            // 文稿里的照片（P9-02）：只认这个房间的文件
            val docImageIds = latestBodies.values.flatMap { DocumentImages.fileIds(it) }.distinct()
            val fileEntries = if (!includeFiles) emptyList() else {
                // 聊天里的附件、审稿各版本的原文件、文稿里的照片
                val ids = (messages.mapNotNull { it.file?.id } + reviewVersions.map { it.fileId } + docImageIds).distinct()
                if (ids.isEmpty()) emptyList() else Files.select(Files.id, Files.fileName, Files.storagePath)
                    .where { (Files.id inList ids) and (Files.roomId eq roomId) }
                    .map { Triple(it[Files.id], unique(it[Files.id].toString().take(8) + "-" + it[Files.fileName].substringBeforeLast('.'), "." + it[Files.fileName].substringAfterLast('.', "bin")), it[Files.storagePath]) }
            }
            val zipNames = fileEntries.associate { (id, name, _) -> id to name }
            val files = fileEntries.map { (_, name, path) -> name to path }
            // 图片标记换成附件里的路径；没导出附件（或不是这个房间的文件）就写「（照片）」
            val docs = documents.filter { it.id in latestBodies }.map { d ->
                unique(d.title, ".md") to DocumentImages.rewrite(latestBodies[d.id]!!) { alt, id ->
                    zipNames[id]?.let { "![$alt](../附件/$it)" } ?: "（${alt.ifBlank { "照片" }}）"
                }
            }
            ExportBundle(room.name, json, chat, docs, files)
        }

    /** 把导出包写成 ZIP（流式写出，附件直接从存储读）。 */
    fun write(bundle: ExportBundle, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("说明.txt", README.trimIndent().toByteArray())
            put("data.json", QichiJson.encodeToString(JsonElement.serializer(), bundle.json).toByteArray())
            put("聊天记录.md", bundle.chatMarkdown.toByteArray())
            bundle.documents.forEach { (name, body) -> put("文稿/$name", body.toByteArray()) }
            bundle.files.forEach { (name, path) ->
                val file = storage.resolve(path).toFile()
                if (!file.exists()) return@forEach
                zip.putNextEntry(ZipEntry("附件/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private companion object {
        const val README = """
            栖迟 · 房间数据导出

            data.json      全部内容（聊天、心情、待办、日程、问答、计划、灵感、文稿、留言、档案、决定、阅读、总结、审稿批注），机器可读
            聊天记录.md    按时间排好的聊天记录
            文稿/          每篇文稿的最新版本（Markdown）
            附件/          聊天里的照片和文件、审稿各版本的原文件（导出时选了才有）

            不包含：撤回的内容、回收站里的内容、对方没有共享的书中标注、对方还没揭晓的问答回答。
        """
    }
}
