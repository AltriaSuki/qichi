package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.CreateAnnotationReplyRequest
import app.qichi.shared.api.CreateAnnotationRequest
import app.qichi.shared.api.NormRect
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReviewDocument
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.TextBlock
import app.qichi.shared.api.UpdateAnnotationRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ReviewFormat
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
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
class ReviewRepositoryTest {
    private lateinit var db: QichiDatabase
    private lateinit var reviews: ReviewRepository
    private val server = FakeServer()
    private val t0 = Instant.parse("2026-09-21T10:00:00Z")
    private val doc = ReviewDocument(UUID.randomUUID(), roomId, 1, t0, t0, null, null, "报价方案", me, 1)
    private val version = ReviewVersion(UUID.randomUUID(), roomId, 2, t0, t0, null, null, doc.id, 1, UUID.randomUUID(), "quote.pdf",
        ReviewFormat.Pdf, me, PreviewStatus.Ready, 1, null)
    private val anchor = AnnotationAnchor(1, AnchorKind.Paragraph, NormRect(0.1, 0.2, 0.5, 0.05), "p1-b2", "  单价 1200  ")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val store = LocalStore(db)
        val api = SyncFixtures.api(server.engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        reviews = ReviewRepository(context, db, store, FileRepository(api), api, SyncEngine(api, db, store), SyncScheduler(context), session)
        store.applyServer(doc)
        store.applyServer(version)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `离线批注：先写本机（等待发送），发件箱里是创建请求；空的不写`() = runTest {
        server.online = false
        val a = reviews.annotate(doc, version, anchor, AnnotationKind.Proposal, "  改成 1100 ")!!
        val local = reviews.observeAnnotations(roomId).first().single()
        assertTrue(local.isPending)
        assertEquals("改成 1100", local.value.body)
        assertEquals("单价 1200", local.value.anchor.quote)
        val op = db.outbox().all().single()
        assertEquals("rooms/$roomId/reviews/${doc.id}/annotations", op.path)
        val req = QichiJson.decodeFromString(CreateAnnotationRequest.serializer(), op.bodyJson!!)
        assertEquals(a.id, req.id)
        assertEquals(version.id, req.versionId)
        assertEquals(AnnotationKind.Proposal, req.kind)
        assertNull(reviews.annotate(doc, version, anchor, AnnotationKind.Comment, "   "))
    }

    @Test
    fun `接受、重新打开、回复都走发件箱`() = runTest {
        val a = reviews.annotate(doc, version, anchor, AnnotationKind.Comment, "数字不对")!!
        reviews.setStatus(a, AnnotationStatus.Accepted)
        val accepted = reviews.observeAnnotations(roomId).first().single().value
        assertEquals(AnnotationStatus.Accepted, accepted.status)
        assertEquals(me, accepted.resolvedBy)
        reviews.setStatus(accepted, AnnotationStatus.Open)
        assertNull(reviews.observeAnnotations(roomId).first().single().value.resolvedBy)

        reviews.reply(a, "我去问问")
        val ops = db.outbox().all()
        assertEquals(
            listOf(UpdateAnnotationRequest(status = Patch.of(AnnotationStatus.Accepted)), UpdateAnnotationRequest(status = Patch.of(AnnotationStatus.Open))),
            ops.filter { it.method == "PATCH" }.map { QichiJson.decodeFromString(UpdateAnnotationRequest.serializer(), it.bodyJson!!) },
        )
        val replyOp = ops.last()
        assertEquals("rooms/$roomId/annotations/${a.id}/replies", replyOp.path)
        assertEquals("我去问问", QichiJson.decodeFromString(CreateAnnotationReplyRequest.serializer(), replyOp.bodyJson!!).body)
        assertEquals(1, reviews.observeReplies(roomId).first().size)
    }

    @Test
    fun `预览页取过一次就存在本机，之后离线也能看`() = runTest {
        val pages = listOf(ReviewPage(1, 595.0, 842.0, UUID.randomUUID(), listOf(TextBlock("p1-b1", AnchorKind.Paragraph, NormRect(0.1, 0.1, 0.5, 0.05), "标题")), emptyList()))
        server.custom = { req ->
            if (req.url.encodedPath.endsWith("/versions/1/pages")) {
                respond(QichiJson.encodeToString(ListSerializer(ReviewPage.serializer()), pages), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else null
        }
        assertEquals(pages, reviews.pages(doc, version))
        server.online = false
        assertEquals(pages, reviews.pages(doc, version))
        assertEquals(pages, reviews.cachedPages(version))
    }
}
