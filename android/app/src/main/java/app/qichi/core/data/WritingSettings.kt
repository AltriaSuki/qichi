package app.qichi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 共同写作编辑器的字号与行距：只存在这台手机上（两个人各自习惯不同）。 */
data class WritingSettings(val fontSize: Int = 17, val lineHeight: Float = 2.1f) {
    companion object {
        val FONT_SIZES = listOf(15, 17, 19, 21)
        val LINE_HEIGHTS = listOf(1.7f, 2.1f, 2.5f)
    }
}

interface WritingSettingsStore {
    val settings: Flow<WritingSettings>
    suspend fun set(settings: WritingSettings)
}

private val Context.writingDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_writing")

class DataStoreWritingSettingsStore(private val context: Context) : WritingSettingsStore {
    override val settings: Flow<WritingSettings> = context.writingDataStore.data.map { prefs ->
        WritingSettings(
            fontSize = prefs[FONT_SIZE]?.takeIf { it in WritingSettings.FONT_SIZES } ?: WritingSettings().fontSize,
            lineHeight = prefs[LINE_HEIGHT]?.takeIf { it in WritingSettings.LINE_HEIGHTS } ?: WritingSettings().lineHeight,
        )
    }

    override suspend fun set(settings: WritingSettings) {
        context.writingDataStore.edit {
            it[FONT_SIZE] = settings.fontSize
            it[LINE_HEIGHT] = settings.lineHeight
        }
    }

    private companion object {
        val FONT_SIZE = intPreferencesKey("font_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
    }
}

class InMemoryWritingSettingsStore(initial: WritingSettings = WritingSettings()) : WritingSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WritingSettings> = state
    override suspend fun set(settings: WritingSettings) {
        state.value = settings
    }
}
