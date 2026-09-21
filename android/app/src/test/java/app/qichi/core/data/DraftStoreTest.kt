package app.qichi.core.data

import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.SyncFixtures.roomId
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
class DraftStoreTest {
    private lateinit var db: QichiDatabase
    private lateinit var drafts: DraftStore

    @Before
    fun setUp() {
        db = SyncFixtures.database()
        drafts = DraftStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `每个房间各存一份，空白等于删除`() = runTest {
        val other = UUID.randomUUID()
        drafts.save(roomId, DraftStore.CHAT, "周六早上八点出发？")
        drafts.save(other, DraftStore.CHAT, "另一个房间")
        assertEquals("周六早上八点出发？", drafts.load(roomId, DraftStore.CHAT))
        assertEquals("另一个房间", drafts.load(other, DraftStore.CHAT))

        drafts.save(roomId, DraftStore.CHAT, "  ")
        assertNull(drafts.load(roomId, DraftStore.CHAT))
        drafts.delete(other, DraftStore.CHAT)
        assertNull(drafts.load(other, DraftStore.CHAT))
    }
}
