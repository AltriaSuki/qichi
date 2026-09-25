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
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.core.ui.currentStage
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdatePlanStageRequest
import app.qichi.shared.model.PlanStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlanRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var plans: PlanRepository
    private lateinit var todos: TodoRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val store = LocalStore(db)
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        plans = PlanRepository(db, store, SyncScheduler(context), session)
        todos = TodoRepository(db, store, SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `新建计划：本机立即出现（待发送），发件箱里是建计划请求；可以没有目标日`() = runTest {
        val plan = plans.create(roomId, "  秋天去一次海边 ", partner, targetDate = null)
        val local = plans.observePlans(roomId).first().single()
        assertEquals(SyncState.PENDING, local.syncState)
        assertEquals("秋天去一次海边", local.value.title)
        assertEquals(PlanStatus.Active, local.value.status)
        assertNull(local.value.targetDate)

        val op = db.outbox().all().single()
        assertEquals("rooms/$roomId/plans", op.path)
        assertEquals(CreatePlanRequest(plan.id, "秋天去一次海边", partner), QichiJson.decodeFromString(CreatePlanRequest.serializer(), op.bodyJson!!))
    }

    @Test
    fun `阶段按顺序推进：标记完成后当前阶段移到下一个；发出的是只含 doneAt 的修改`() = runTest {
        val plan = plans.create(roomId, "搬家", me, null)
        listOf("打包", "搬运", "收拾").forEachIndexed { i, t -> plans.addStage(plan, t, i) }
        var stages = plans.observeStages(roomId).first().map { it.value }
        assertEquals("打包", currentStage(stages)?.title)

        plans.setStageDone(stages.first { it.title == "打包" }, done = true)
        stages = plans.observeStages(roomId).first().map { it.value }
        assertEquals("搬运", currentStage(stages)?.title)

        val patch = db.outbox().all().last()
        assertEquals("PATCH", patch.method)
        assertTrue(patch.path.startsWith("rooms/$roomId/plans/${plan.id}/stages/"))
        val body = QichiJson.decodeFromString(UpdatePlanStageRequest.serializer(), patch.bodyJson!!)
        assertTrue(body.doneAt is Patch.Value && (body.doneAt as Patch.Value).value != null)
        assertEquals(Patch.Absent, body.title)

        stages.forEach { if (it.doneAt == null) plans.setStageDone(it, true) }
        assertNull(currentStage(plans.observeStages(roomId).first().map { it.value }), "都完成了就没有当前阶段")
    }

    @Test
    fun `修改下一步只发改动字段；完成计划写下完成记录`() = runTest {
        val plan = plans.create(roomId, "搬家", me, null)
        plans.update(plan, UpdatePlanRequest(nextStep = Patch.of("打电话问搬家公司"), nextStepDue = Patch.of(LocalDate.of(2026, 9, 25))))
        val updated = plans.observePlans(roomId).first().single().value
        assertEquals("打电话问搬家公司", updated.nextStep)
        val body = QichiJson.decodeFromString(UpdatePlanRequest.serializer(), db.outbox().all().last().bodyJson!!)
        assertEquals(Patch.Absent, body.title)
        assertEquals(Patch.of("打电话问搬家公司"), body.nextStep)

        plans.complete(updated, "  搬完了，比想的顺利。 ")
        val done = plans.observePlans(roomId).first().single().value
        assertEquals(PlanStatus.Done, done.status)
        assertEquals("搬完了，比想的顺利。", done.completionNote)
        assertNotNull(done.completedAt)
        assertEquals("rooms/$roomId/plans/${plan.id}/complete", db.outbox().all().last().path)
    }

    @Test
    fun `换封面：本机立即换上，发出的只含封面；改回插画发 null`() = runTest {
        val plan = plans.create(roomId, "秋天去一次海边", me, null)
        val photo = java.util.UUID.randomUUID()
        plans.update(plan, UpdatePlanRequest(coverFileId = Patch.of(photo)))
        val covered = plans.observePlans(roomId).first().single().value
        assertEquals(photo, covered.coverFileId)
        val body = QichiJson.decodeFromString(UpdatePlanRequest.serializer(), db.outbox().all().last().bodyJson!!)
        assertEquals(Patch.of(photo), body.coverFileId)
        assertEquals(Patch.Absent, body.title)

        plans.update(covered, UpdatePlanRequest(coverFileId = Patch.of(null)))
        assertEquals(null, plans.observePlans(roomId).first().single().value.coverFileId)
        assertEquals(Patch.of(null), QichiJson.decodeFromString(UpdatePlanRequest.serializer(), db.outbox().all().last().bodyJson!!).coverFileId)
    }

    @Test
    fun `在计划里加待办：待办带上计划 id；删除计划后计划不再出现，但待办还在`() = runTest {
        val plan = plans.create(roomId, "搬家", me, null)
        val todo = todos.create(roomId, "买纸箱", planId = plan.id)
        assertEquals(plan.id, todo.planId)
        val request = QichiJson.decodeFromString(CreateTodoRequest.serializer(), db.outbox().all().last().bodyJson!!)
        assertEquals(plan.id, request.planId)

        plans.delete(plan)
        assertTrue(plans.observePlans(roomId).first().isEmpty())
        assertEquals(listOf("买纸箱"), todos.observeTodos(roomId).first().map { it.value.title })
    }
}
