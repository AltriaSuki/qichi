package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TodoRepositoryTest {
    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore
    private lateinit var todos: TodoRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        store = LocalStore(db)
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        todos = TodoRepository(db, store, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun titles() = db.entities().observeByType(roomId.toString(), EntityType.Todo.wireName).first()
        .map { LocalStore.toLocal<app.qichi.shared.api.Todo>(it).value }.filter { it.doneAt == null }.map { it.id }

    @Test
    fun `取消完成重复待办：本机马上拿掉自动生成的下一次`() = runTest {
        val done = SyncFixtures.todo("背单词", seq = 1).copy(recurrence = "FREQ=DAILY", doneAt = SyncFixtures.t0)
        val next = SyncFixtures.todo("背单词", seq = 2).copy(recurrence = "FREQ=DAILY", recurrencePrevId = done.id)
        store.applyServer(done)
        store.applyServer(next)
        assertEquals(listOf(next.id), titles())

        todos.reopen(done)
        assertEquals(listOf(done.id), titles())
        assertEquals("rooms/$roomId/todos/${done.id}/reopen", db.outbox().all().single().path)
    }

    @Test
    fun `普通待办取消完成不动别的`() = runTest {
        val done = SyncFixtures.todo("取快递", seq = 1).copy(doneAt = SyncFixtures.t0)
        val other = SyncFixtures.todo("买菜", seq = 2, id = UUID.randomUUID())
        store.applyServer(done)
        store.applyServer(other)
        todos.reopen(done)
        assertEquals(setOf(done.id, other.id), titles().toSet())
    }
}
