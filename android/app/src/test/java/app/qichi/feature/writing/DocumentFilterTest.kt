package app.qichi.feature.writing

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.core.sync.SyncFixtures.roomId
import app.qichi.shared.api.Document
import app.qichi.shared.model.DocCategory
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class DocumentFilterTest {
    private val t = Instant.parse("2026-09-24T02:00:00Z")
    private fun doc(title: String, category: DocCategory? = null, pinned: Boolean = false) =
        Local(Document(UUID.randomUUID(), roomId, 1, t, t, null, null, title, UUID.randomUUID(), 1, null, 10, pinned, category), SyncState.SYNCED)

    private val trip = doc("东山岛游记", DocCategory.Travel, pinned = true)
    private val letter = doc("给明年的信", DocCategory.Letter)
    private val list = doc("周末清单")
    private val all = listOf(trip, letter, list)

    @Test
    fun `不搜不筛：全部，顺序不变`() {
        assertEquals(all, filterDocuments(all, "  ", null, emptyMap()).map { it.doc })
    }

    @Test
    fun `按分类筛`() {
        assertEquals(listOf(letter), filterDocuments(all, "", DocCategory.Letter, emptyMap()).map { it.doc })
    }

    @Test
    fun `搜索：标题本机就能搜；正文命中带上一小段；和分类一起用`() {
        val hits = filterDocuments(all, "信", null, mapOf(list.value.id to "…记得带信封…"))
        assertEquals(listOf(letter to null, list to "…记得带信封…"), hits.map { it.doc to it.snippet })
        assertEquals(listOf(letter), filterDocuments(all, "信", DocCategory.Letter, mapOf(list.value.id to "…")).map { it.doc })
    }
}
