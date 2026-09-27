package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncFixtures.t0
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.DocComment
import app.qichi.shared.api.Document
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Plan
import app.qichi.shared.api.PlanLog
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.Todo
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.PlanStatus
import app.qichi.shared.model.TrashType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrashRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var trash: TrashRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        trash = TrashRepository(db, store, api, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    private fun mood(author: UUID, deletedAt: java.time.Instant?) = Mood(
        id = UUID.randomUUID(), roomId = roomId, seq = 1, createdAt = t0, updatedAt = t0, deletedAt = deletedAt,
        deletedBy = author.takeIf { deletedAt != null }, authorId = author, label = MoodLabel.Calm, intensity = 3,
        note = null, needsComfort = false,
    )

    private fun deletedTodo(title: String, parent: UUID? = null, at: java.time.Instant = t0.plusSeconds(60)): Todo =
        SyncFixtures.todo(title, seq = 1).copy(parentId = parent, deletedAt = at, deletedBy = me)

    @Test
    fun `列表：最近删除的在前；随父待办删掉的子任务不单独列；对方的心情不列`() = runTest {
        val parent = deletedTodo("搬家")
        val child = deletedTodo("打包书", parent = parent.id)
        val myMood = mood(me, t0.plusSeconds(120))
        val theirMood = mood(partner, t0.plusSeconds(180))
        val message = SyncFixtures.pendingMessage("说错话了").copy(seq = 3, createdSeq = 3, deletedAt = t0.plusSeconds(240), deletedBy = partner)
        listOf(parent, child, myMood, theirMood, message).forEach { store.applyServer(it) }
        store.applyServer(SyncFixtures.todo("没删的", seq = 2))

        val entries = trash.observe(roomId).first()
        assertEquals(listOf(TrashType.Message, TrashType.Mood, TrashType.Todo), entries.map { it.type })
        assertEquals(listOf(message.id, myMood.id, parent.id), entries.map { it.id })
    }

    @Test
    fun `计划的进展记录（P14-03）：只列自己记的；计划也删了时随计划一起不单独列；恢复发 restore`() = runTest {
        val plan = Plan(
            id = UUID.randomUUID(), roomId = roomId, seq = 1, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
            title = "去海边", ownerId = me, status = PlanStatus.Active, targetDate = null, nextStep = null, nextStepOwnerId = null,
            nextStepDue = null, completedAt = null, completionNote = null,
        )
        fun log(author: UUID, planId: UUID) = PlanLog(
            id = UUID.randomUUID(), roomId = roomId, seq = 2, createdAt = t0, updatedAt = t0, deletedAt = t0.plusSeconds(60),
            deletedBy = author, planId = planId, authorId = author, body = "进展",
        )
        val mine = log(me, plan.id)
        val theirs = log(partner, plan.id)
        val deletedPlan = plan.copy(id = UUID.randomUUID(), deletedAt = t0.plusSeconds(90), deletedBy = me)
        val withPlan = log(me, deletedPlan.id)
        listOf(plan, mine, theirs, deletedPlan, withPlan).forEach { store.applyServer(it) }

        val entries = trash.observe(roomId).first()
        assertEquals(listOf(deletedPlan.id, mine.id), entries.map { it.id })
        trash.restore(roomId, entries.single { it.id == mine.id })
        assertNull(store.get<PlanLog>(EntityType.PlanLog, mine.id)!!.value.deletedAt)
        assertEquals("rooms/$roomId/trash/plan_log/${mine.id}/restore", db.outbox().all().single().path)
    }

    @Test
    fun `恢复：本机立即回来（子任务一起），发件箱里是 restore 请求`() = runTest {
        val parent = deletedTodo("搬家")
        val child = deletedTodo("打包书", parent = parent.id)
        val earlierChild = deletedTodo("单独删掉的", parent = parent.id, at = t0)
        listOf(parent, child, earlierChild).forEach { store.applyServer(it) }

        trash.restore(roomId, trash.observe(roomId).first().single())

        assertNull(store.get<Todo>(EntityType.Todo, parent.id)!!.value.deletedAt)
        assertNull(store.get<Todo>(EntityType.Todo, child.id)!!.value.deletedAt)
        assertTrue(store.get<Todo>(EntityType.Todo, earlierChild.id)!!.value.deletedAt != null, "更早单独删掉的子任务不跟着回来")
        val op = db.outbox().all().single()
        assertEquals("POST", op.method)
        assertEquals("rooms/$roomId/trash/todo/${parent.id}/restore", op.path)
        assertEquals(OutboxOp.KIND_CHANGE, op.kind)
    }

    @Test
    fun `彻底删除：本机连同子任务一起删掉，发件箱里是 DELETE`() = runTest {
        val parent = deletedTodo("搬家")
        val child = deletedTodo("打包书", parent = parent.id)
        listOf(parent, child).forEach { store.applyServer(it) }

        trash.purge(roomId, trash.observe(roomId).first().single())

        assertNull(store.get<Todo>(EntityType.Todo, parent.id))
        assertNull(store.get<Todo>(EntityType.Todo, child.id))
        val op = db.outbox().all().single()
        assertEquals("DELETE", op.method)
        assertEquals("rooms/$roomId/trash/todo/${parent.id}", op.path)
        assertTrue(trash.observe(roomId).first().isEmpty())
    }

    private fun review(deletedAt: Instant?) = ReviewDocument(
        id = UUID.randomUUID(), roomId = roomId, seq = 1, createdAt = t0, updatedAt = t0, deletedAt = deletedAt,
        deletedBy = me.takeIf { deletedAt != null }, title = "方案初稿", createdBy = me, latestVersion = 1,
    )

    private fun annotation(documentId: UUID, author: UUID, deletedAt: Instant? = t0.plusSeconds(60)) = Annotation(
        id = UUID.randomUUID(), roomId = roomId, seq = 2, createdAt = t0, updatedAt = t0, deletedAt = deletedAt,
        deletedBy = author.takeIf { deletedAt != null }, documentId = documentId, versionId = UUID.randomUUID(),
        anchor = AnnotationAnchor(page = 1, kind = AnchorKind.Region), authorId = author, kind = AnnotationKind.Comment,
        status = AnnotationStatus.Open, body = "这里再斟酌", carriedFromId = null, anchorLost = false, resolvedBy = null, resolvedAt = null,
    )

    private fun document(deletedAt: Instant?) = Document(
        id = UUID.randomUUID(), roomId = roomId, seq = 1, createdAt = t0, updatedAt = t0, deletedAt = deletedAt,
        deletedBy = me.takeIf { deletedAt != null }, title = "东山岛游记", createdBy = me, latestVersion = 1, latestAuthorId = me, charCount = 100,
    )

    private fun comment(documentId: UUID, author: UUID, parent: UUID? = null, deletedAt: Instant? = t0.plusSeconds(60)) = DocComment(
        id = UUID.randomUUID(), roomId = roomId, seq = 3, createdAt = t0, updatedAt = t0, deletedAt = deletedAt,
        deletedBy = author.takeIf { deletedAt != null }, documentId = documentId, parentId = parent, authorId = author,
        body = "这段可以短一点", quote = null, version = null, resolvedAt = null, resolvedBy = null,
    )

    @Test
    fun `审稿文件、批注、文稿留言（P15-03）：批注和留言只列自己写的；所属的审稿文件、文稿也删了时不单独列`() = runTest {
        val liveReview = review(null)
        val deletedReview = review(t0.plusSeconds(300))
        val myAnnotation = annotation(liveReview.id, me)
        val theirAnnotation = annotation(liveReview.id, partner)
        val withDeletedReview = annotation(deletedReview.id, me)
        val liveDoc = document(null)
        val deletedDoc = document(t0.plusSeconds(200))
        val myComment = comment(liveDoc.id, me)
        val theirComment = comment(liveDoc.id, partner)
        val withDeletedDoc = comment(deletedDoc.id, me)
        listOf(liveReview, deletedReview, myAnnotation, theirAnnotation, withDeletedReview, liveDoc, deletedDoc, myComment, theirComment, withDeletedDoc)
            .forEach { store.applyServer(it) }

        val entries = trash.observe(roomId).first()
        assertEquals(setOf(deletedReview.id, deletedDoc.id, myAnnotation.id, myComment.id), entries.map { it.id }.toSet())
        assertEquals(TrashType.ReviewDocument, entries.first().type, "最近删除的在前")
    }

    @Test
    fun `恢复审稿文件、批注、留言：本机立即回来，发 restore`() = runTest {
        val liveReview = review(null)
        val myAnnotation = annotation(liveReview.id, me)
        val liveDoc = document(null)
        val myComment = comment(liveDoc.id, me)
        val deletedReview = review(t0.plusSeconds(300))
        listOf(liveReview, myAnnotation, liveDoc, myComment, deletedReview).forEach { store.applyServer(it) }

        trash.observe(roomId).first().forEach { trash.restore(roomId, it) }

        assertNull(store.get<Annotation>(EntityType.Annotation, myAnnotation.id)!!.value.deletedAt)
        assertNull(store.get<DocComment>(EntityType.DocComment, myComment.id)!!.value.deletedAt)
        assertNull(store.get<ReviewDocument>(EntityType.ReviewDocument, deletedReview.id)!!.value.deletedAt)
        assertEquals(
            setOf(
                "rooms/$roomId/trash/annotation/${myAnnotation.id}/restore",
                "rooms/$roomId/trash/doc_comment/${myComment.id}/restore",
                "rooms/$roomId/trash/review_document/${deletedReview.id}/restore",
            ),
            db.outbox().all().map { it.path }.toSet(),
        )
        assertTrue(trash.observe(roomId).first().isEmpty())
    }

    @Test
    fun `彻底删除审稿文件：本机连同批注和批注下的讨论一起删；删留言连同它的回复，别的讨论不动`() = runTest {
        val deletedReview = review(t0.plusSeconds(300))
        val note = annotation(deletedReview.id, partner, deletedAt = null)
        val reply = AnnotationReply(UUID.randomUUID(), roomId, 4, t0, t0, null, null, note.id, me, "好")
        val liveDoc = document(null)
        val thread = comment(liveDoc.id, me)
        val threadReply = comment(liveDoc.id, partner, parent = thread.id, deletedAt = null)
        val otherThread = comment(liveDoc.id, me, deletedAt = null)
        listOf(deletedReview, note, reply, liveDoc, thread, threadReply, otherThread).forEach { store.applyServer(it) }

        trash.observe(roomId).first().forEach { trash.purge(roomId, it) }

        assertNull(store.get<ReviewDocument>(EntityType.ReviewDocument, deletedReview.id))
        assertNull(store.get<Annotation>(EntityType.Annotation, note.id))
        assertNull(store.get<AnnotationReply>(EntityType.AnnotationReply, reply.id))
        assertNull(store.get<DocComment>(EntityType.DocComment, thread.id))
        assertNull(store.get<DocComment>(EntityType.DocComment, threadReply.id))
        assertTrue(store.get<DocComment>(EntityType.DocComment, otherThread.id) != null, "别的讨论不动")
        assertEquals(
            setOf("rooms/$roomId/trash/review_document/${deletedReview.id}", "rooms/$roomId/trash/doc_comment/${thread.id}"),
            db.outbox().all().map { it.path }.toSet(),
        )
        assertEquals(setOf("DELETE"), db.outbox().all().map { it.method }.toSet())
    }
}
