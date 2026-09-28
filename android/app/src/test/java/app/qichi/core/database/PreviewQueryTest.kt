package app.qichi.core.database

import app.qichi.core.data.documentPreview
import app.qichi.core.sync.SyncFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** 写作列表的预览只读正文开头一段（P17-06），做出来的预览和读全文一样。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PreviewQueryTest {
    private val db = SyncFixtures.database()

    @After
    fun tearDown() = db.close()

    @Test
    fun `长文稿只读开头，预览不变`() = runTest {
        val body = "# 给明年秋天的信\n\n" + "今天去看了海，风很大。".repeat(2_000)
        db.documentVersions().upsert(
            DocumentVersionRow("v1", SyncFixtures.roomId.toString(), "d1", 1, 0, SyncFixtures.me.toString(), body.length, null, 0, body),
        )
        val row = db.documentVersions().observeLatestBodies(SyncFixtures.roomId.toString()).first().single()
        assertEquals(PREVIEW_SOURCE_CHARS, row.body.length)
        assertEquals(documentPreview(body), documentPreview(row.body))
    }
}
