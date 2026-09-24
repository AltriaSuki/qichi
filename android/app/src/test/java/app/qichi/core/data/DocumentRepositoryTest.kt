package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.OutboxProcessor
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.core.sync.bodyText
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.Document
import app.qichi.shared.api.DocumentVersion
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.util.CjkText
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DocumentRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var docs: DocumentRepository
    private lateinit var processor: OutboxProcessor
    private val server = FakeServer()

    /** 服务端上这篇文稿的最新版本号；保存请求按它判断基线 */
    private var latest = 0

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val store = LocalStore(db)
        val api = SyncFixtures.api(server.engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        docs = DocumentRepository(db, store, api, DraftStore(db), SyncScheduler(context), session)
        processor = OutboxProcessor(api, db, store)
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        server.custom = { request ->
            val path = request.url.encodedPath
            when {
                request.method.value == "POST" && path.endsWith("/versions") -> {
                    val req = QichiJson.decodeFromString(SaveDocumentVersionRequest.serializer(), request.bodyText())
                    if (req.baseVersion != latest) {
                        respond(
                            """{"type":"x","title":"文稿已有更新的版本","status":409,"code":"conflict_version","latestVersion":$latest}""",
                            HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    } else {
                        latest++
                        val docId = java.util.UUID.fromString(path.split("/").dropLast(1).last())
                        val v = DocumentVersion(req.id, docId, latest, req.baseVersion, me, CjkText.charCount(req.body), req.restoredFromVersion, Instant.now(), req.body)
                        respond(QichiJson.encodeToString(DocumentVersion.serializer(), v), HttpStatusCode.Created, json)
                    }
                }
                request.method.value == "POST" && path.endsWith("/documents") -> {
                    val req = QichiJson.decodeFromString(CreateDocumentRequest.serializer(), request.bodyText())
                    val now = Instant.now()
                    val d = Document(req.id, roomId, 1, now, now, null, null, req.title, me, 0, null, 0)
                    respond(QichiJson.encodeToString(Document.serializer(), d), HttpStatusCode.Created, json)
                }
                else -> null
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun draft(doc: Document) = docs.observeDraft(roomId, doc.id).first()

    @Test
    fun `段落旁留言：先写本机再走发件箱；回复、解决都一样；删掉讨论开头连回复一起不显示`() = runTest {
        val doc = docs.create(roomId, "海边周末")!!
        processor.drain()
        assertTrue(db.outbox().all().isEmpty())
        server.online = false

        assertNull(docs.addComment(doc, "周六早上八点出发。", "   "), "空白不建")
        val root = docs.addComment(doc, "  周六早上\n八点出发。 ", " 会不会太早？ ")!!
        assertEquals("周六早上 八点出发。", root.quote)
        assertEquals("会不会太早？", root.body)
        val reply = docs.reply(root, "那就九点")!!
        assertEquals(root.id, reply.parentId)
        docs.setResolved(root, true)

        val list = docs.observeComments(roomId, doc.id).first()
        assertEquals(listOf(root.id, reply.id), list.map { it.value.id })
        assertTrue(list.all { it.syncState == SyncState.PENDING })
        assertNotNull(list.first().value.resolvedAt)
        assertEquals(
            listOf("rooms/$roomId/documents/${doc.id}/comments", "rooms/$roomId/documents/${doc.id}/comments", "rooms/$roomId/doc-comments/${root.id}/resolve"),
            db.outbox().all().map { it.path },
        )

        docs.deleteComment(list.first().value)
        assertTrue(docs.observeComments(roomId, doc.id).first().isEmpty(), "开头删了，回复也不显示")
    }

    @Test
    fun `新建文稿先出现在本机（待发送），发出后同步完成；标题空白不建`() = runTest {
        assertNull(docs.create(roomId, "   "))
        val doc = docs.create(roomId, " 给明年秋天的信 ")!!
        val local = docs.observeDocuments(roomId).first().single()
        assertEquals("给明年秋天的信", local.value.title)
        assertEquals(SyncState.PENDING, local.syncState)
        processor.drain()
        assertEquals(SyncState.SYNCED, docs.observeDocument(roomId, doc.id).first()!!.syncState)
    }

    @Test
    fun `每次输入都存成草稿；保存后版本进本机缓存，草稿和保存的一样就清掉`() = runTest {
        val doc = docs.create(roomId, "信")!!
        processor.drain()
        docs.writeDraft(roomId, doc.id, "窗边的绿萝又长了一截。", baseVersion = 0, baseBody = null)
        assertEquals(0, draft(doc)!!.baseVersion)

        assertTrue(docs.save(doc))
        assertTrue(docs.observeSaving(doc.id).first())
        assertFalse(docs.save(doc), "已经有一次保存在排队，不重复放进发件箱")
        val request = QichiJson.decodeFromString(SaveDocumentVersionRequest.serializer(), db.outbox().all().single().bodyJson!!)
        assertEquals(0, request.baseVersion)
        assertEquals(OutboxOp.KIND_DOC_VERSION, db.outbox().all().single().kind)

        processor.drain()
        assertFalse(docs.observeSaving(doc.id).first())
        assertNull(draft(doc))
        val cached = docs.observeVersions(doc.id).first().single()
        assertEquals(1, cached.version)
        assertEquals("窗边的绿萝又长了一截。", cached.body)
        assertEquals("窗边的绿萝又长了一截。", docs.loadBody(roomId, doc.id, 1), "离线也能读已经保存的版本")

        // 改回和最新版本一样：没有未保存的内容
        docs.writeDraft(roomId, doc.id, "窗边的绿萝又长了一截。你说", 1, cached.body)
        docs.writeDraft(roomId, doc.id, "窗边的绿萝又长了一截。", 1, cached.body)
        assertNull(draft(doc))
    }

    @Test
    fun `保存发出去之后又接着写：成功后草稿保留新写的部分，改为基于新版本`() = runTest {
        val doc = docs.create(roomId, "信")!!
        processor.drain()
        docs.writeDraft(roomId, doc.id, "第一段", 0, null)
        docs.save(doc)
        docs.writeDraft(roomId, doc.id, "第一段\n第二段", 0, null)
        processor.drain()
        val d = assertNotNull(draft(doc))
        assertEquals("第一段\n第二段", d.text)
        assertEquals(1, d.baseVersion)
    }

    @Test
    fun `对方先存了新版本：保存被拒绝，自己的内容一字不丢；重基线后能保存为下一个版本`() = runTest {
        val doc = docs.create(roomId, "信")!!
        processor.drain()
        latest = 2 // 对方已经存到 v2
        docs.writeDraft(roomId, doc.id, "我在 v1 上写的内容", 1, "v1 的正文")
        docs.save(doc)
        processor.drain()
        assertTrue(db.outbox().all().isEmpty(), "被拒绝的保存从发件箱里拿掉")
        assertFalse(docs.observeSaving(doc.id).first())
        val kept = assertNotNull(draft(doc))
        assertEquals("我在 v1 上写的内容", kept.text)
        assertEquals(1, kept.baseVersion)
        assertEquals(SyncState.SYNCED, docs.observeDocument(roomId, doc.id).first()!!.syncState, "文稿本身的同步不受影响")

        docs.rebase(roomId, doc.id, latestVersion = 2)
        assertEquals(2, draft(doc)!!.baseVersion)
        docs.save(doc)
        processor.drain()
        assertNull(draft(doc))
        assertEquals(3, docs.observeVersions(doc.id).first().first().version)
    }

    @Test
    fun `旧版另存为新版：带上来源版本号，基于最新版本`() = runTest {
        val doc = docs.create(roomId, "信")!!
        processor.drain()
        latest = 4
        docs.restore(doc.copy(latestVersion = 4), version = 2, body = "第二稿")
        val request = QichiJson.decodeFromString(SaveDocumentVersionRequest.serializer(), db.outbox().all().single().bodyJson!!)
        assertEquals(4, request.baseVersion)
        assertEquals(2, request.restoredFromVersion)
        processor.drain()
        assertNull(draft(doc))
        assertEquals(2, docs.observeVersions(doc.id).first().single().restoredFromVersion)
    }
}
