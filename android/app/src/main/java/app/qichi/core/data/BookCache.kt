package app.qichi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.qichi.core.network.ApiClient
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val Context.readingDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_reading")

/**
 * 书籍离线缓存（docs/05-sync-offline.md）：整本 EPUB 下载到手机上，离线能读。
 * 总占用有上限（默认 500MB，可以改），超出时按「最近打开时间」淘汰最旧的书；正在读的书不淘汰。
 */
class BookCache(
    private val context: Context,
    private val api: ApiClient,
) {
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
    suspend fun open(fileId: UUID, onProgress: (Float) -> Unit = {}): File {
        val file = fileOf(fileId)
        if (!file.exists()) {
            api.download("files/$fileId", file) { received, total -> if (total != null && total > 0) onProgress(received.toFloat() / total) }
            _cached.update { it + fileId }
        }
        withContext(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) }
        evict(keep = fileId)
        return file
    }

    /** 自己刚加的书：直接放进缓存，不用再下载一遍。 */
    suspend fun put(fileId: UUID, source: File) = withContext(Dispatchers.IO) {
        source.copyTo(fileOf(fileId), overwrite = true)
        _cached.update { it + fileId }
    }

    suspend fun remove(fileId: UUID) = withContext(Dispatchers.IO) {
        fileOf(fileId).delete()
        _cached.update { it - fileId }
    }

    /** 超出上限时，从最久没打开的开始删，[keep]（正在读的）不删。 */
    suspend fun evict(keep: UUID?) = withContext(Dispatchers.IO) {
        val limit = limitBytes.first()
        val files = dir.listFiles { f -> f.name.endsWith(".epub") }?.sortedBy { it.lastModified() }?.toMutableList() ?: return@withContext
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            if (f.nameWithoutExtension == keep?.toString()) continue
            total -= f.length()
            f.delete()
        }
        _cached.value = scan()
    }

    private companion object {
        val LIMIT = longPreferencesKey("cache_limit_bytes")
    }
}
