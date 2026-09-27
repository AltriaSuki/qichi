package app.qichi.feature.widget

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.TodoRepository
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.Todo
import app.qichi.shared.model.EntityType
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 在桌面组件上勾掉、撤回：走的是和 App 里一样的写入（P15-02）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TodoWidgetActionsTest {
    private lateinit var db: QichiDatabase
    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext<android.app.Application>())
        db = SyncFixtures.database()
        store = LocalStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.actions(tokens: AuthTokens? = SyncFixtures.tokens()): TodoWidgetActions {
        val session = SessionManager(SyncFixtures.api(FakeServer().engine), InMemoryTokenStore(tokens), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return TodoWidgetActions(session, TodoRepository(db, store, SyncScheduler(context), session))
    }

    private suspend fun local(id: UUID): Local<Todo> = checkNotNull(store.get<Todo>(EntityType.Todo, id))

    private suspend fun sent() = db.outbox().all().map { it.path }

    @Test
    fun `勾掉：先写本机（我勾的、待发送），经发件箱发出和 App 里一样的请求；连点两下只勾一次`() = runTest {
        val actions = actions()
        val todo = SyncFixtures.todo("取回干洗的外套", seq = 1)
        store.applyServer(todo)

        assertTrue(actions.complete(todo.id))
        assertFalse(actions.complete(todo.id), "已经勾掉了，不再发一次")

        val done = local(todo.id)
        assertEquals(SyncFixtures.me, done.value.doneBy)
        assertTrue(done.isPending)
        assertEquals(listOf("rooms/$roomId/todos/${todo.id}/complete"), sent())
    }

    @Test
    fun `撤回：回到没做完，发的是和 App 里取消完成一样的请求；没勾过的、本机没有的不动`() = runTest {
        val actions = actions()
        val todo = SyncFixtures.todo("订周六的餐位", seq = 1)
        store.applyServer(todo)

        assertFalse(actions.reopen(todo.id), "还没勾过")
        assertFalse(actions.complete(UUID.randomUUID()), "本机没有这一条")
        assertTrue(actions.complete(todo.id))
        assertTrue(actions.reopen(todo.id))

        assertNull(local(todo.id).value.doneAt)
        assertEquals(listOf("rooms/$roomId/todos/${todo.id}/complete", "rooms/$roomId/todos/${todo.id}/reopen"), sent())
    }

    @Test
    fun `没登录时点组件什么都不做`() = runTest {
        val actions = actions(tokens = null)
        val todo = SyncFixtures.todo("取快递", seq = 1)
        store.applyServer(todo)

        assertFalse(actions.complete(todo.id))
        assertNull(local(todo.id).value.doneAt)
        assertEquals(emptyList(), sent())
    }
}
