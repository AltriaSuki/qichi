package app.qichi.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 记下「这个房间有新版 App 才懂的内容」（P13-07，docs/05-sync-offline.md §3.2）。
 *
 * 服务端比 App 新时，同步里认不出来的实体被跳过（或者新字段被丢掉），同步位置照常前进。这里记下是哪个版本的 App 跳过的：
 * - App 升级后发现记录比自己旧 → 对这个房间重新快照，把跳过的补回来（[needsRebootstrap]）
 * - 记录就是当前版本留下的 → 要等更新的 App，界面提示更新（[needsNewerApp]）
 *
 * 记录放在 [Store] 里（正式是 SharedPreferences，不改数据库结构）；登出时清掉。
 */
class UnknownContent(private val store: Store, private val currentVersion: Int) {

    interface Store {
        fun all(): Map<String, Int>
        fun put(roomId: String, version: Int)
        fun remove(roomId: String)
        fun clear()
    }

    private val _needsNewerApp = MutableStateFlow(computeNeedsNewerApp())

    /** 当前版本也有认不出来的内容：提示更新 */
    val needsNewerApp: StateFlow<Boolean> = _needsNewerApp.asStateFlow()

    /**
     * 这个房间有当前版本认不出来的内容，在写入（同步位置前进）之前调用。
     * 已经有更旧版本留下、还没补回来的记录时留着它：先重新快照。
     */
    @Synchronized
    fun mark(roomId: UUID) {
        val previous = store.all()[roomId.toString()]
        if (previous == null || previous >= currentVersion) store.put(roomId.toString(), currentVersion)
        refresh()
    }

    /** 重新快照写完之后：还有认不出来的就记成当前版本（等更新），全都认得就清掉。 */
    @Synchronized
    fun rebootstrapped(roomId: UUID, incomplete: Boolean) {
        if (incomplete) store.put(roomId.toString(), currentVersion) else store.remove(roomId.toString())
        refresh()
    }

    /** 记录是更旧的 App 留下的：该重新快照了。 */
    fun needsRebootstrap(roomId: UUID): Boolean = store.all()[roomId.toString()]?.let { it < currentVersion } == true

    /** 登出：记录跟着本机数据一起清掉。 */
    @Synchronized
    fun clearAll() {
        store.clear()
        refresh()
    }

    private fun refresh() {
        _needsNewerApp.value = computeNeedsNewerApp()
    }

    private fun computeNeedsNewerApp(): Boolean = store.all().values.any { it >= currentVersion }

    /** 放在内存里（测试用）。 */
    class MemoryStore : Store {
        private val map = HashMap<String, Int>()
        override fun all(): Map<String, Int> = synchronized(map) { map.toMap() }
        override fun put(roomId: String, version: Int) { synchronized(map) { map[roomId] = version } }
        override fun remove(roomId: String) { synchronized(map) { map.remove(roomId) } }
        override fun clear() { synchronized(map) { map.clear() } }
    }
}
