package app.qichi.core.data

import androidx.test.core.app.ApplicationProvider
import app.qichi.core.sync.FakeServer
import app.qichi.core.sync.SyncFixtures
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookCacheTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var cache: BookCache
    private lateinit var source: File

    @Before fun setUp() {
        File(context.filesDir, "books").deleteRecursively()
        cache = BookCache(context, SyncFixtures.api(FakeServer().engine))
        source = File(context.cacheDir, "cache-test.epub").apply { writeBytes(ByteArray(8)) }
    }

    @After fun clean() {
        source.delete()
        File(context.filesDir, "books").deleteRecursively()
    }

    @Test fun `连续加书也遵守缓存上限`() = runTest {
        cache.setLimit(10)
        val old = UUID.randomUUID()
        val next = UUID.randomUUID()
        cache.put(old, source)
        File(context.filesDir, "books/$old.epub").setLastModified(1)
        cache.put(next, source)
        assertTrue(cache.usedBytes() <= 10)
        assertEquals(setOf(next), cache.cached.first())
    }

    @Test fun `阅读期间不淘汰正在使用的书并在释放后清理`() = runTest {
        cache.setLimit(100)
        val id = UUID.randomUUID()
        cache.put(id, source)
        val first = cache.acquire(id)
        cache.acquire(id)
        cache.setLimit(0)
        assertTrue(first.exists())
        cache.remove(id)
        assertTrue(first.exists())
        cache.release(id)
        assertTrue(first.exists())
        cache.release(id)
        assertFalse(first.exists())
        assertTrue(cache.cached.first().isEmpty())
    }
}
