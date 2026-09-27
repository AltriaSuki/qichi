package app.qichi.server.export

import app.qichi.server.archive.toArchiveItem
import app.qichi.server.board.toBoardPost
import app.qichi.server.board.toBoardTopic
import app.qichi.server.db.AiFindings
import app.qichi.server.db.AnnotationReplies
import app.qichi.server.db.Annotations
import app.qichi.server.db.Answers
import app.qichi.server.db.ArchiveItems
import app.qichi.server.db.BoardPosts
import app.qichi.server.db.BoardTopics
import app.qichi.server.db.Books
import app.qichi.server.db.Decisions
import app.qichi.server.db.DocComments
import app.qichi.server.db.DocumentVersions
import app.qichi.server.db.Documents
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
import app.qichi.server.db.ReviewDocuments
import app.qichi.server.db.ReviewVersions
import app.qichi.server.db.Summaries
import app.qichi.server.db.Todos
import app.qichi.server.db.tx
import app.qichi.server.decisions.toDecision
import app.qichi.server.documents.toDocComment
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
import app.qichi.server.review.reviewVersionQuery
import app.qichi.server.review.toAiFinding
import app.qichi.server.review.toAnnotation
import app.qichi.server.review.toAnnotationReply
import app.qichi.server.review.toReviewDocument
import app.qichi.server.review.toReviewVersion
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.server.summaries.toSummary
import app.qichi.server.sync.Visibility
import app.qichi.server.todos.toTodo
import app.qichi.shared.api.AiFinding
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.Answer
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Book
import app.qichi.shared.api.Decision
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.Document
import app.qichi.shared.api.Event
import app.qichi.shared.api.Highlight
import app.qichi.shared.api.Idea
import app.qichi.shared.api.Member
import app.qichi.shared.api.Message
import app.qichi.shared.api.Milestone
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.PlanStage
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.QnaRound
import app.qichi.shared.api.Question
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.Room
import app.qichi.shared.api.Summary
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MessageKind
import app.qichi.shared.rules.DocumentImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
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

/**
 * 房间数据导出（P7-05）：一个 ZIP——data.json（全部内容）、聊天记录.md、文稿/、说明.txt，可选 附件/。
 * 不含撤回的内容、回收站里的内容、对方没共享的书中标记（docs/05-sync-offline.md）。
 *
 * 边读边写（P13-16）：内容在一个一致性快照里读，消息一页一页读、读一页写一页（data.json 和聊天记录.md 各读一遍），
 * 其余各类读一类写一类，附件直接从存储读。内存里只有一页消息和量小的内容，房间再大也不会把服务端的内存占满。
 */
class ExportService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val storage: FileStorage,
    private val clock: Clock,
) {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 不是房间成员就 404。在开始写响应之前调用，免得写到一半才报错。 */
    suspend fun checkAccess(userId: UUID, roomId: UUID) = db.tx(readOnly = true) { rooms.requireMember(roomId, userId) }

    /** 把整个导出包写成 ZIP 写到 [out]（写完不关 [out]）。 */
    suspend fun export(userId: UUID, roomId: UUID, includeFiles: Boolean, out: OutputStream) {
        val zip = ZipOutputStream(out)
        val attachments = db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            rooms.requireMember(roomId, userId)
            writeContent(zip, userId, roomId, includeFiles)
        }
        // 附件在磁盘上：读数据库的事务已经结束，不再占着连接
        withContext(Dispatchers.IO) {
            for ((name, path) in attachments) {
                val file = storage.resolve(path).toFile()
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("附件/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
    }

    /** 在事务里写出 说明.txt、data.json、聊天记录.md、文稿/；返回要写的附件（ZIP 里的名字 → 存储路径）。 */
    private fun writeContent(zip: ZipOutputStream, userId: UUID, roomId: UUID, includeFiles: Boolean): List<Pair<String, String>> {
        val room = RoomRepository.room(roomId)!!
        val members = RoomRepository.allMembers(roomId)
        val names = members.associate { it.userId to it.displayName }
        val zone = runCatching { ZoneId.of(room.timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))

        val documents = Documents.selectAll().where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() }.map { it.toDocument() }
        // 每篇文稿的最新版正文，一次查出
        val latestBodies = DocumentVersions.join(Documents, JoinType.INNER, DocumentVersions.documentId, Documents.id)
            .select(DocumentVersions.documentId, DocumentVersions.body)
            .where { (Documents.roomId eq roomId) and Documents.deletedAt.isNull() and (DocumentVersions.version eq Documents.latestVersion) }
            .associate { it[DocumentVersions.documentId] to it[DocumentVersions.body] }
        // 审稿：不含回收站里的审稿文件和它下面的一切
        val reviewDocs = ReviewDocuments.selectAll().where { (ReviewDocuments.roomId eq roomId) and ReviewDocuments.deletedAt.isNull() }.map { it.toReviewDocument() }
        val liveDocs = reviewDocs.map { it.id }.toSet()
        val reviewVersions = reviewVersionQuery().where { ReviewVersions.roomId eq roomId }.map { it.toReviewVersion() }.filter { it.documentId in liveDocs }
        val annotations = Annotations.selectAll().where { (Annotations.roomId eq roomId) and Annotations.deletedAt.isNull() }
            .map { it.toAnnotation() }.filter { it.documentId in liveDocs }

        val usedNames = mutableSetOf<String>()
        fun unique(base: String, ext: String): String {
            val clean = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "未命名" }
            var name = "$clean$ext"
            var i = 2
            while (!usedNames.add(name)) name = "$clean ($i)$ext".also { i++ }
            return name
        }
        // 附件：聊天里的照片和文件、审稿各版本的原文件、文稿里的照片（P9-02，只认这个房间的文件）
        val fileEntries = if (!includeFiles) emptyList() else {
            val messageFiles = Messages.select(Messages.fileId)
                .where { (Messages.roomId eq roomId) and Visibility.quotableMessage() and Messages.fileId.isNotNull() }
                .orderBy(Messages.createdSeq, SortOrder.ASC)
                .mapNotNull { it[Messages.fileId] }
            val docImageIds = latestBodies.values.flatMap { DocumentImages.fileIds(it) }
            val ids = (messageFiles + reviewVersions.map { it.fileId } + docImageIds).distinct()
            if (ids.isEmpty()) emptyList() else Files.select(Files.id, Files.fileName, Files.storagePath)
                .where { (Files.id inList ids) and (Files.roomId eq roomId) }
                .map { Triple(it[Files.id], unique(it[Files.id].toString().take(8) + "-" + it[Files.fileName].substringBeforeLast('.'), "." + it[Files.fileName].substringAfterLast('.', "bin")), it[Files.storagePath]) }
        }
        val zipNames = fileEntries.associate { (id, name, _) -> id to name }
        // 图片标记换成附件里的路径；没导出附件（或不是这个房间的文件）就写「（照片）」
        val docs = documents.filter { it.id in latestBodies }.map { d ->
            unique(d.title, ".md") to DocumentImages.rewrite(latestBodies[d.id]!!) { alt, id ->
                zipNames[id]?.let { "![$alt](../附件/$it)" } ?: "（${alt.ifBlank { "照片" }}）"
            }
        }

        fun entry(name: String, write: (OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            write(zip)
            zip.closeEntry()
        }
        entry("说明.txt") { it.write(README.trimIndent().toByteArray()) }
        entry("data.json") { out ->
            JsonObjectWriter(out).apply {
                value("format", String.serializer(), "qichi-export-1")
                value("exportedAt", String.serializer(), clock.instant().toString())
                value("room", Room.serializer(), room)
                value("members", ListSerializer(Member.serializer()), members)
                array("messages", Message.serializer()) { emit -> forEachMessage(roomId, emit) }
                list("moods", Mood.serializer(), Moods.selectAll().where { (Moods.roomId eq roomId) and Moods.deletedAt.isNull() }.map { it.toMood() })
                list("todos", Todo.serializer(), Todos.selectAll().where { (Todos.roomId eq roomId) and Todos.deletedAt.isNull() }.map { it.toTodo() })
                list("events", Event.serializer(), Events.selectAll().where { (Events.roomId eq roomId) and Events.deletedAt.isNull() }.map { it.toEvent() })
                list("questions", Question.serializer(), Questions.selectAll().where { (Questions.roomId eq roomId) and Questions.deletedAt.isNull() }.map { it.toQuestion() })
                list("qnaRounds", QnaRound.serializer(), QnaRounds.selectAll().where { QnaRounds.roomId eq roomId }.map { it.toQnaRound() })
                // 还没揭晓的回答只导出自己的
                list("answers", Answer.serializer(), Visibility.answers(
                    Answers.selectAll().where { (Answers.roomId eq roomId) and Answers.deletedAt.isNull() }.map { it.toAnswer() }, userId))
                list("plans", Plan.serializer(), Plans.selectAll().where { (Plans.roomId eq roomId) and Plans.deletedAt.isNull() }.map { it.toPlan() })
                list("planStages", PlanStage.serializer(), PlanStages.selectAll().where { (PlanStages.roomId eq roomId) and PlanStages.deletedAt.isNull() }.map { it.toPlanStage() })
                list("milestones", Milestone.serializer(), Milestones.selectAll().where { (Milestones.roomId eq roomId) and Milestones.deletedAt.isNull() }.map { it.toMilestone() })
                list("planLogs", PlanLog.serializer(), PlanLogs.selectAll().where { (PlanLogs.roomId eq roomId) and PlanLogs.deletedAt.isNull() }.map { it.toPlanLog() })
                list("ideas", Idea.serializer(), Ideas.selectAll().where { (Ideas.roomId eq roomId) and Ideas.deletedAt.isNull() }.map { it.toIdea() })
                list("documents", Document.serializer(), documents)
                list("boardTopics", BoardTopic.serializer(), BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.map { it.toBoardTopic() })
                list("boardPosts", BoardPost.serializer(), BoardPosts.selectAll().where { (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() }.map { it.toBoardPost() })
                list("archiveItems", ArchiveItem.serializer(), ArchiveItems.selectAll().where { (ArchiveItems.roomId eq roomId) and ArchiveItems.deletedAt.isNull() }.map { it.toArchiveItem() })
                list("decisions", Decision.serializer(), Decisions.selectAll().where { (Decisions.roomId eq roomId) and Decisions.deletedAt.isNull() }.map { it.toDecision() })
                list("books", Book.serializer(), bookQuery().where { (Books.roomId eq roomId) and Books.deletedAt.isNull() }.map { it.toBook() })
                list("readingProgress", ReadingProgress.serializer(), ReadingProgressTable.selectAll().where { ReadingProgressTable.roomId eq roomId }.map { it.toReadingProgress() })
                list("highlights", Highlight.serializer(), Visibility.highlights(
                    Highlights.selectAll().where { (Highlights.roomId eq roomId) and Highlights.deletedAt.isNull() }.map { it.toHighlight() }, userId))
                list("summaries", Summary.serializer(), Summaries.selectAll().where { (Summaries.roomId eq roomId) and Summaries.deletedAt.isNull() }.map { it.toSummary() })
                list("reviewDocuments", ReviewDocument.serializer(), reviewDocs)
                list("reviewVersions", ReviewVersion.serializer(), reviewVersions)
                list("annotations", Annotation.serializer(), annotations)
                list("aiFindings", AiFinding.serializer(), AiFindings.selectAll().where { AiFindings.roomId eq roomId }
                    .map { it.toAiFinding() }.filter { it.documentId in liveDocs })
                // 文稿留言（P9-03）：不含回收站里的，和已删文稿的
                val liveDocIds = documents.map { it.id }.toSet()
                val docComments = DocComments.selectAll().where { (DocComments.roomId eq roomId) and DocComments.deletedAt.isNull() }
                    .map { it.toDocComment() }.filter { it.documentId in liveDocIds }
                val liveRoots = docComments.filter { it.parentId == null }.map { it.id }.toSet()
                list("docComments", DocComment.serializer(), docComments.filter { it.parentId == null || it.parentId in liveRoots })
                val annotationIds = annotations.map { it.id }.toSet()
                list("annotationReplies", AnnotationReply.serializer(), AnnotationReplies.selectAll()
                    .where { (AnnotationReplies.roomId eq roomId) and AnnotationReplies.deletedAt.isNull() }
                    .map { it.toAnnotationReply() }.filter { it.annotationId in annotationIds })
                end()
            }
        }
        entry("聊天记录.md") { out ->
            val w = out.bufferedWriter()
            w.write("# ${room.name} · 聊天记录\n\n")
            forEachMessage(roomId) { m ->
                val who = when (m.kind) {
                    MessageKind.Ai -> "AI"
                    MessageKind.System -> "系统"
                    else -> names[m.authorId] ?: "其中一人"
                }
                val text = buildString {
                    if (m.file != null) append(if (m.kind == MessageKind.Image) "[图片 ${m.file!!.fileName}] " else "[文件 ${m.file!!.fileName}] ")
                    append(m.body)
                }.trim()
                w.write("**${m.createdAt.atZone(zone).format(time)} $who**：$text\n\n")
            }
            // 只冲出去，不关：关了会把整个 ZIP 关掉
            w.flush()
        }
        docs.forEach { (name, body) -> entry("文稿/$name") { it.write(body.toByteArray()) } }
        return fileEntries.map { (_, name, path) -> name to path }
    }

    /** 按先后一页一页读出能导出的消息（没删、没撤回），读一页交出一页。 */
    private fun forEachMessage(roomId: UUID, block: (Message) -> Unit) {
        var after = Long.MIN_VALUE
        while (true) {
            val page = messageQuery()
                .where { (Messages.roomId eq roomId) and Visibility.quotableMessage() and (Messages.createdSeq greater after) }
                .orderBy(Messages.createdSeq, SortOrder.ASC)
                .limit(MESSAGE_PAGE)
                .map { it.toMessage() }
            page.forEach(block)
            if (page.size < MESSAGE_PAGE) return
            after = page.last().createdSeq
        }
    }

    /** 一边生成一边写出的 JSON 对象：一个字段一个字段写，列表一项一项写，不在内存里拼出整个 data.json。 */
    private class JsonObjectWriter(private val out: OutputStream) {
        private var first = true

        init {
            raw("{")
        }

        private fun raw(text: String) = out.write(text.toByteArray())

        private fun key(name: String) {
            raw((if (first) "" else ",") + QichiJson.encodeToString(String.serializer(), name) + ":")
            first = false
        }

        fun <T> value(name: String, serializer: KSerializer<T>, value: T) {
            key(name)
            raw(QichiJson.encodeToString(serializer, value))
        }

        fun <T> list(name: String, serializer: KSerializer<T>, items: List<T>) = array(name, serializer) { emit -> items.forEach(emit) }

        fun <T> array(name: String, serializer: KSerializer<T>, produce: ((T) -> Unit) -> Unit) {
            key(name)
            raw("[")
            var firstItem = true
            produce { item ->
                raw((if (firstItem) "" else ",") + QichiJson.encodeToString(serializer, item))
                firstItem = false
            }
            raw("]")
        }

        fun end() = raw("}")
    }

    private companion object {
        /** 导出时一次读多少条消息 */
        const val MESSAGE_PAGE = 500

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
