package app.qichi.core.auth

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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

    override suspend fun readEnd(): SessionEnd? = withContext(Dispatchers.IO) {
        val reason = prefs.getString(END_REASON, null)?.let { r -> SessionEndReason.entries.firstOrNull { it.name == r } }
        val at = prefs.getLong(END_AT, -1L).takeIf { it >= 0 }
        if (reason == null || at == null) null else SessionEnd(reason, Instant.ofEpochMilli(at))
    }

    override suspend fun writeEnd(end: SessionEnd?) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply {
                if (end == null) {
                    remove(END_REASON)
                    remove(END_AT)
                } else {
                    putString(END_REASON, end.reason.name)
                    putLong(END_AT, end.at.toEpochMilli())
                }
            }.commit()
        }
    }

    private companion object {
        const val KEY = "owner"
        const val END_REASON = "end_reason"
        const val END_AT = "end_at"
    }
}
