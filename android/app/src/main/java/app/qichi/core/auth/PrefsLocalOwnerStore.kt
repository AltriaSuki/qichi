package app.qichi.core.auth

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** [LocalOwnerStore] 放在 SharedPreferences（只是一个用户 id，不算机密）；立刻写盘。 */
@SuppressLint("ApplySharedPref")
class PrefsLocalOwnerStore(context: Context) : LocalOwnerStore {
    private val prefs = context.getSharedPreferences("qichi-local-owner", Context.MODE_PRIVATE)

    override suspend fun read(): UUID? = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    override suspend fun write(userId: UUID?) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply { if (userId == null) remove(KEY) else putString(KEY, userId.toString()) }.commit()
        }
    }

    private companion object {
        const val KEY = "owner"
    }
}
