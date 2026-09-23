package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.me
import app.qichi.core.sync.SyncFixtures.partner
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.DecisionConcern
import app.qichi.shared.api.Patch
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.UpdateDecisionRequest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DecisionRepositoryTest {

    private lateinit var db: QichiDatabase
    private lateinit var decisions: DecisionRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = SyncFixtures.database()
        val api = SyncFixtures.api(FakeServer().engine)
        val session = SessionManager(api, InMemoryTokenStore(SyncFixtures.tokens()), emptySet(), "test", TestScope(testScheduler))
        testScheduler.advanceUntilIdle()
        decisions = DecisionRepository(db, LocalStore(db), SyncScheduler(context), session)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `新建时备选去掉空白和重复；改关注点只动自己那条，发出的只有这个字段`() = runTest {
        val d = decisions.create(roomId, " 搬到哪里？ ", listOf("A", " ", "B", "A"), null)!!
        assertEquals(listOf("A", "B"), d.options)
        // 对方的关注点已经同步下来
        val withPartner = d.copy(concerns = listOf(DecisionConcern(partner, "房租")))
        decisions.update(withPartner, UpdateDecisionRequest(myConcern = Patch.of(" 通勤 ")))
        val local = decisions.observeDecisions(roomId).first().single().value
        assertEquals(mapOf(partner to "房租", me to "通勤"), local.concerns.associate { it.userId to it.text })
        val body = QichiJson.decodeFromString(UpdateDecisionRequest.serializer(), db.outbox().all().last().bodyJson!!)
        assertEquals(UpdateDecisionRequest(myConcern = Patch.of(" 通勤 ")), body)
    }

    @Test
    fun `定下来本机先记下时间和人；重新考虑清空`() = runTest {
        val d = decisions.create(roomId, "周末去哪", listOf("海边", "山里"), null)!!
        decisions.update(d, UpdateDecisionRequest(finalChoice = Patch.of("海边")))
        val decided = decisions.observeDecisions(roomId).first().single().value
        assertEquals("海边", decided.finalChoice)
        assertNotNull(decided.decidedAt)
        assertEquals(me, decided.decidedBy)
        decisions.update(decided, UpdateDecisionRequest(finalChoice = Patch.of(null)))
        val reopened = decisions.observeDecisions(roomId).first().single().value
        assertNull(reopened.finalChoice)
        assertNull(reopened.decidedBy)
    }
}
