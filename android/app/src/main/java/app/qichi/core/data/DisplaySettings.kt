package app.qichi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.qichi.core.designsystem.SkyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** 「我的 → 显示」：只存在这台手机上，退出登录也保留。 */
data class DisplaySettings(
    /** 「大字」：字号 ×1.2 */
    val largeText: Boolean = false,
    /** 「减少动画」：天色与页面切换直接完成 */
    val reduceMotion: Boolean = false,
    /** 天色：跟着时间（默认）/ 跟手机深色模式 / 总是白天 / 总是深夜 */
    val skyMode: SkyMode = SkyMode.Auto,
) {
    val textSizeLabel: String get() = if (largeText) "大字" else "标准"
}

interface DisplaySettingsStore {
    val settings: Flow<DisplaySettings>
    suspend fun setLargeText(on: Boolean)
    suspend fun setReduceMotion(on: Boolean)
    suspend fun setSkyMode(mode: SkyMode)
}

private val Context.displayDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_display")

class DataStoreDisplaySettingsStore(private val context: Context) : DisplaySettingsStore {
    private val store get() = context.displayDataStore

    override val settings: Flow<DisplaySettings> = store.data.map { prefs ->
        DisplaySettings(
            largeText = prefs[LARGE_TEXT] ?: false,
            reduceMotion = prefs[REDUCE_MOTION] ?: false,
            skyMode = prefs[SKY_MODE]?.let { name -> SkyMode.entries.firstOrNull { it.name == name } } ?: SkyMode.Auto,
        )
    }

    override suspend fun setLargeText(on: Boolean) {
        store.edit { it[LARGE_TEXT] = on }
    }

    override suspend fun setReduceMotion(on: Boolean) {
        store.edit { it[REDUCE_MOTION] = on }
    }

    override suspend fun setSkyMode(mode: SkyMode) {
        store.edit { it[SKY_MODE] = mode.name }
    }

    private companion object {
        val LARGE_TEXT = booleanPreferencesKey("large_text")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val SKY_MODE = stringPreferencesKey("sky_mode")
    }
}

/** 内存实现（测试用）。 */
class InMemoryDisplaySettingsStore(initial: DisplaySettings = DisplaySettings()) : DisplaySettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<DisplaySettings> = state
    override suspend fun setLargeText(on: Boolean) = state.update { it.copy(largeText = on) }
    override suspend fun setReduceMotion(on: Boolean) = state.update { it.copy(reduceMotion = on) }
    override suspend fun setSkyMode(mode: SkyMode) = state.update { it.copy(skyMode = mode) }
}
