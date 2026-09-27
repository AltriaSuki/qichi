package app.qichi.core.sync

import android.annotation.SuppressLint
import android.content.Context

/**
 * [UnknownContent] 的记录（房间 id → 当时的 App 版本号）放在 SharedPreferences。
 * 用 commit 立刻写盘：同步位置前进之前一定已经记下（都在后台线程调用）。
 */
@SuppressLint("ApplySharedPref")
class PrefsUnknownContentStore(context: Context) : UnknownContent.Store {
    private val prefs = context.getSharedPreferences("qichi-unknown-content", Context.MODE_PRIVATE)

    override fun all(): Map<String, Int> = prefs.all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()

    override fun put(roomId: String, version: Int) {
        prefs.edit().putInt(roomId, version).commit()
    }

    override fun remove(roomId: String) {
        prefs.edit().remove(roomId).commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
