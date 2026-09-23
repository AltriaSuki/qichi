package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.reading.EpubOpener
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.Book
import app.qichi.shared.api.PutReadingProgressRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.UpdateHighlightRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.model.HighlightKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReadingRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var reading: ReadingRepository
    private val t0 = Instant.parse("2026-09-21T10:00:00Z")
    private val book = Book(UUID.randomUUID(), roomId, 1, t0, t0, null, null, "海边的旅店", null, UUID.randomUUID(), 1000, me, null, null)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        reading = ReadingRepository(context, db, store, FileRepository(api), api, BookCache(context, api), EpubOpener(context), SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `翻页时连续保存进度：本机只有一条，发件箱里只留最新的一次`() = runTest {
        reading.saveProgress(book, """{"p":1}""", 0.1)
        reading.saveProgress(book, """{"p":2}""", 0.2)
        reading.saveProgress(book, """{"p":3}""", 0.3)
        val local = reading.observeProgress(roomId).first().single()
        assertEquals(0.3, local.progress)
        val op = db.outbox().all().single()
        assertEquals(OutboxOp.KIND_READING_PROGRESS, op.kind)
        assertEquals("""{"p":3}""", QichiJson.decodeFromString(PutReadingProgressRequest.serializer(), op.bodyJson!!).locator)
    }

    @Test
    fun `服务端已有我的进度（另一台手机建的）：响应回来后本机只剩服务端那条`() = runTest {
        reading.saveProgress(book, """{"p":1}""", 0.1)
        val local = reading.observeProgress(roomId).first().single()
        val server = local.copy(id = UUID.randomUUID(), seq = 9, progress = 0.1)
        db.outbox().all().forEach { db.outbox().delete(it.localId) }
        store.applyReadingProgress(server)
        assertEquals(listOf(server.id), reading.observeProgress(roomId).first().map { it.id })
    }

    @Test
    fun `标注：先出现在本机；改成共享只发改动的字段；删除后不再显示`() = runTest {
        val h = reading.addHighlight(book, HighlightKind.Highlight, "{}", "不必急着去哪里", " 我也喜欢 ", shared = false)!!
        assertEquals("我也喜欢", reading.observeHighlights(roomId).first().single().value.note)
        reading.updateHighlight(h, h.note, shared = true)
        val patch = QichiJson.decodeFromString(UpdateHighlightRequest.serializer(), db.outbox().all().last().bodyJson!!)
        assertEquals(UpdateHighlightRequest(shared = Patch.of(true)), patch)
        reading.deleteHighlight(reading.observeHighlights(roomId).first().single().value)
        assertEquals(0, reading.observeHighlights(roomId).first().size)
    }
}
