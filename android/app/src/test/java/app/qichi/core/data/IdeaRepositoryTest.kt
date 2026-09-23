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
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.UpdateIdeaRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IdeaRepositoryTest {
    private lateinit var db: QichiDatabase
    private lateinit var ideas: IdeaRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        ideas = IdeaRepository(db, LocalStore(db), SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `记下：本机立即出现（待发送），空白不记，最新的在前`() = runTest {
        assertNull(ideas.add(roomId, "   "))
        val first = ideas.add(roomId, " 阳台种柠檬树 ")!!
        Thread.sleep(2)
        val second = ideas.add(roomId, "周末去看展")!!
        val list = ideas.observeIdeas(roomId).first()
        assertEquals(listOf(second.id, first.id), list.map { it.value.id })
        assertEquals("阳台种柠檬树", list.last().value.body)
        assertEquals(me, list.last().value.authorId)
        assertTrue(list.all { it.syncState == SyncState.PENDING })
        val op = db.outbox().all().first()
        assertEquals("rooms/$roomId/ideas", op.path)
        assertEquals(CreateIdeaRequest(first.id, "阳台种柠檬树"), QichiJson.decodeFromString(CreateIdeaRequest.serializer(), op.bodyJson!!))
    }

    @Test
    fun `修改只在有变化时发出；删除后不再出现`() = runTest {
        val idea = ideas.add(roomId, "看展")!!
        ideas.edit(idea, "看展 ")
        assertEquals(1, db.outbox().all().size, "内容没变不发请求")
        ideas.edit(idea, "周末去看展")
        assertEquals(UpdateIdeaRequest("周末去看展"), QichiJson.decodeFromString(UpdateIdeaRequest.serializer(), db.outbox().all().last().bodyJson!!))
        ideas.delete(ideas.observeIdeas(roomId).first().single().value)
        assertTrue(ideas.observeIdeas(roomId).first().isEmpty())
        assertEquals("DELETE", db.outbox().all().last().method)
    }
}
