package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.SyncState
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.ReviseArchiveItemRequest
import app.qichi.shared.model.ArchiveKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArchiveRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var archive: ArchiveRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        archive = ArchiveRepository(db, LocalStore(db), api, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `断网记下一条档案：本机立即出现，发件箱里带来源消息`() = runTest {
        val source = UUID.randomUUID()
        val item = archive.create(roomId, ArchiveKind.Consensus, " 吵架先停十分钟 ", "  ", source)!!
        val local = archive.observeItems(roomId).first().single()
        assertEquals(SyncState.PENDING, local.syncState)
        assertEquals("吵架先停十分钟", local.value.title)
        assertEquals("", local.value.body)
        val req = QichiJson.decodeFromString(CreateArchiveItemRequest.serializer(), db.outbox().all().single().bodyJson!!)
        assertEquals(CreateArchiveItemRequest(item.id, ArchiveKind.Consensus, "吵架先停十分钟", "", source), req)
        assertNull(archive.create(roomId, ArchiveKind.Consensus, "  ", "x", null))
    }

    @Test
    fun `连续两次修订的基线依次递增；什么都没改不产生修订`() = runTest {
        val item = archive.create(roomId, ArchiveKind.Preference, "喜欢安静的餐厅", "", null)!!
        archive.revise(item, "喜欢安静的餐厅", "", null)
        assertEquals(1, db.outbox().all().size)
        archive.revise(item, "喜欢安静的餐厅", "最好靠窗。", null)
        val once = archive.observeItems(roomId).first().single().value
        assertEquals(2, once.currentRevision)
        archive.revise(once, "喜欢安静、靠窗的餐厅", "", null)
        val bases = db.outbox().all().drop(1).map { QichiJson.decodeFromString(ReviseArchiveItemRequest.serializer(), it.bodyJson!!).baseRevision }
        assertEquals(listOf(1, 2), bases)
        assertEquals("rooms/$roomId/archive/${item.id}/revisions", db.outbox().all().last().path)
    }
}
