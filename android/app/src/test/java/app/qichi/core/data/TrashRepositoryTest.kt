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
import app.qichi.shared.api.Mood
import app.qichi.shared.api.Todo
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MoodLabel
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
}
