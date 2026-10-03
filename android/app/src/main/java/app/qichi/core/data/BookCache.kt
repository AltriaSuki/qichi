package app.qichi.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import app.qichi.core.network.ApiClient
import app.qichi.core.network.percentProgress
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.util.UUID

/**
 * 书籍离线缓存（docs/05-sync-offline.md）：整本 EPUB 下载到手机上，离线能读。
 * 总占用有上限（默认 500MB，可以改），超出时按「最近打开时间」淘汰最旧的书；正在读的书不淘汰。
 */
class BookCache(
    private val context: Context,
    private val api: ApiClient,
) {
    private val mutex = Mutex()
    private val pins = mutableMapOf<UUID, Int>()
    private val dir: File get() = File(context.filesDir, "books").apply { mkdirs() }
    private fun fileOf(fileId: UUID) = File(dir, "$fileId.epub")

    /** 本机已经有的书（文件 id）；下载、淘汰后更新，书架上据此显示「已下载」。 */
    private val _cached = MutableStateFlow(scan())
    val cached: Flow<Set<UUID>> = _cached

    val limitBytes: Flow<Long> = context.readingDataStore.data.map { it[LIMIT] ?: Limits.BOOK_CACHE_DEFAULT_BYTES }

    suspend fun setLimit(bytes: Long) {
        context.readingDataStore.edit { it[LIMIT] = bytes }
        evict(keep = null)
    }

    fun usedBytes(): Long = dir.listFiles { f -> f.name.endsWith(".epub") }?.sumOf { it.length() } ?: 0L

    private fun scan(): Set<UUID> =
        dir.listFiles { f -> f.name.endsWith(".epub") }?.mapNotNull { runCatching { UUID.fromString(it.nameWithoutExtension) }.getOrNull() }?.toSet() ?: emptySet()

    /** 本机有就直接用（并记一次「最近打开」），没有就下载（需要联网）。 */
    suspend fun open(fileId: UUID, onProgress: (Float) -> Unit = {}): File = mutex.withLock {
        openLocked(fileId, onProgress)
    }

    /** Publication 存活期间持有文件；同一本书有多个阅读页时按引用数保护。 */
    suspend fun acquire(fileId: UUID, onProgress: (Float) -> Unit = {}): File = mutex.withLock {
        pins[fileId] = (pins[fileId] ?: 0) + 1
        try {
            openLocked(fileId, onProgress)
        } catch (e: Exception) {
            unpin(fileId)
            throw e
        }
    }

    suspend fun release(fileId: UUID) = withContext(NonCancellable) {
        mutex.withLock {
            unpin(fileId)
            evictLocked(keep = null)
        }
    }

    private fun unpin(fileId: UUID) {
        val count = pins[fileId] ?: return
        if (count <= 1) pins.remove(fileId) else pins[fileId] = count - 1
    }

    private suspend fun openLocked(fileId: UUID, onProgress: (Float) -> Unit): File {
        val file = fileOf(fileId)
        if (!file.exists()) {
            val report = percentProgress(onProgress)
            api.download("files/$fileId", file) { received, total -> if (total != null) report(received, total) }
            _cached.update { it + fileId }
        }
        withContext(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) }
        evictLocked(keep = fileId)
        return file
    }

    /** 自己刚加的书：直接放进缓存，不用再下载一遍。 */
    suspend fun put(fileId: UUID, source: File) = mutex.withLock {
        withContext(Dispatchers.IO) { source.copyTo(fileOf(fileId), overwrite = true) }
        evictLocked(keep = fileId)
    }

    suspend fun remove(fileId: UUID) = mutex.withLock {
        if (fileId !in pins) withContext(Dispatchers.IO) {
            if (fileOf(fileId).delete()) _cached.update { it - fileId }
        }
    }

    /** 超出上限时，从最久没打开的开始删，[keep]（正在读的）不删。 */
    suspend fun evict(keep: UUID?) = mutex.withLock { evictLocked(keep) }

    private suspend fun evictLocked(keep: UUID?) = withContext(Dispatchers.IO) {
        val limit = limitBytes.first()
        val files = dir.listFiles { f -> f.name.endsWith(".epub") }?.sortedBy { it.lastModified() }?.toMutableList() ?: return@withContext
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            if (f.nameWithoutExtension == keep?.toString() || runCatching { UUID.fromString(f.nameWithoutExtension) }.getOrNull() in pins) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
        _cached.value = scan()
    }

    private companion object {
        val LIMIT = longPreferencesKey("cache_limit_bytes")
    }
}
