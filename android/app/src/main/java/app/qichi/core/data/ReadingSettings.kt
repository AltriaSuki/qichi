package app.qichi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 书内阅读的字号、行距、页边距、翻页方式（P20-01）：只存在这台手机上，两个人各自习惯不同。
 * [fontScale] 乘在「大字」之上；[lineHeight] 为 0 表示照原书排版；[volumeKeys]：音量键翻页。
 */
data class ReadingSettings(
    val fontScale: Float = 1f,
    val lineHeight: Float = ORIGINAL,
    val margins: Float = 1f,
    val scroll: Boolean = false,
    val volumeKeys: Boolean = false,
) {
    companion object {
        const val ORIGINAL = 0f
        val FONT_SCALES = listOf(.9f, 1f, 1.15f, 1.3f)
        val LINE_HEIGHTS = listOf(ORIGINAL, 1.6f, 2f)
        val MARGINS = listOf(.6f, 1f, 1.6f)
    }
}

interface ReadingSettingsStore {
    val settings: Flow<ReadingSettings>
    suspend fun set(settings: ReadingSettings)
}

private val Context.readingDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_reading")

class DataStoreReadingSettingsStore(private val context: Context) : ReadingSettingsStore {
    override val settings: Flow<ReadingSettings> = context.readingDataStore.data.map { prefs ->
        val d = ReadingSettings()
        ReadingSettings(
            fontScale = prefs[FONT_SCALE]?.takeIf { it in ReadingSettings.FONT_SCALES } ?: d.fontScale,
            lineHeight = prefs[LINE_HEIGHT]?.takeIf { it in ReadingSettings.LINE_HEIGHTS } ?: d.lineHeight,
            margins = prefs[MARGINS]?.takeIf { it in ReadingSettings.MARGINS } ?: d.margins,
            scroll = prefs[SCROLL] ?: d.scroll,
            volumeKeys = prefs[VOLUME_KEYS] ?: d.volumeKeys,
        )
    }

    override suspend fun set(settings: ReadingSettings) {
        context.readingDataStore.edit {
            it[FONT_SCALE] = settings.fontScale
            it[LINE_HEIGHT] = settings.lineHeight
            it[MARGINS] = settings.margins
            it[SCROLL] = settings.scroll
            it[VOLUME_KEYS] = settings.volumeKeys
        }
    }

    private companion object {
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val MARGINS = floatPreferencesKey("margins")
        val SCROLL = booleanPreferencesKey("scroll")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
    }
}

class InMemoryReadingSettingsStore(initial: ReadingSettings = ReadingSettings()) : ReadingSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<ReadingSettings> = state
    override suspend fun set(settings: ReadingSettings) {
        state.value = settings
    }
}
