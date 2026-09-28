package app.qichi.feature.reading

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.shared.api.Book
import app.qichi.shared.api.ReadingProgress
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Test

class ShelfCurrentBookTest {
    private val room = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val earlier = Instant.parse("2026-09-01T00:00:00Z")
    private val later = earlier.plusSeconds(60)

    private fun book(title: String, progress: Double?, at: Instant): ShelfBook {
        val id = UUID.randomUUID()
        val value = Book(id, room, 1, earlier, earlier, null, null, title, null, UUID.randomUUID(), 100, me, null, null)
        val mine = progress?.let { ReadingProgress(UUID.randomUUID(), room, 1, earlier, at, null, null, id, me, "{}", it) }
        return ShelfBook(Local(value, SyncState.SYNCED), mine, null, true, 0)
    }

    @Test
    fun `打开停在第一页的新书会替换此前的在读书籍`() {
        val old = book("旧书", .45, earlier)
        val justOpened = book("新书", 0.0, later)

        assertEquals(justOpened, currentReadingBook(listOf(old, justOpened)))
    }

    @Test
    fun `没有阅读记录和已读完的书不占在读位置`() {
        val old = book("在读", .45, earlier)
        val unopened = book("未读", null, later)
        val finished = book("读完", 1.0, later)

        assertEquals(old, currentReadingBook(listOf(old, unopened, finished)))
    }
}
