package app.qichi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.qichi.shared.api.Me
import app.qichi.shared.api.QichiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 本机保存的「我」：最近一次 /me 的结果（离线启动时用）和当前选中的房间。 */
interface ProfileStore {
    val me: Flow<Me?>
    val currentRoomId: Flow<UUID?>
    suspend fun saveMe(me: Me)
    suspend fun setCurrentRoom(roomId: UUID?)
    suspend fun clear()
}

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "qichi_profile")

class DataStoreProfileStore(private val context: Context) : ProfileStore {
    private val store get() = context.profileDataStore

    override val me: Flow<Me?> = store.data.map { prefs ->
        prefs[ME]?.let { runCatching { QichiJson.decodeFromString(Me.serializer(), it) }.getOrNull() }
    }

    override val currentRoomId: Flow<UUID?> = store.data.map { prefs ->
        prefs[CURRENT_ROOM]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    override suspend fun saveMe(me: Me) {
        store.edit { it[ME] = QichiJson.encodeToString(Me.serializer(), me) }
    }

    override suspend fun setCurrentRoom(roomId: UUID?) {
        store.edit { if (roomId == null) it.remove(CURRENT_ROOM) else it[CURRENT_ROOM] = roomId.toString() }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val ME = stringPreferencesKey("me")
        val CURRENT_ROOM = stringPreferencesKey("current_room")
    }
}

/** 内存实现（测试用）。 */
class InMemoryProfileStore : ProfileStore {
    private val meFlow = kotlinx.coroutines.flow.MutableStateFlow<Me?>(null)
    private val roomFlow = kotlinx.coroutines.flow.MutableStateFlow<UUID?>(null)
    override val me: Flow<Me?> = meFlow
    override val currentRoomId: Flow<UUID?> = roomFlow
    override suspend fun saveMe(me: Me) { meFlow.value = me }
    override suspend fun setCurrentRoom(roomId: UUID?) { roomFlow.value = roomId }
    override suspend fun clear() { meFlow.value = null; roomFlow.value = null }
    suspend fun snapshot() = me.first()
}
