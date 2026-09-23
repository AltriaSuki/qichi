package app.qichi.core.data

import app.qichi.core.database.DraftRow
import app.qichi.core.database.QichiDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 草稿：只存在这台手机上（聊天输入框、以后的文稿与留言）。切走再回来、杀掉 App 都还在；
 * 退出登录时随本机数据一起清掉。
 */
class DraftStore(
    private val db: QichiDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(roomId: UUID, key: String): String? = db.drafts().get(roomId.toString(), key)?.text

    /** 空白内容等于删除草稿。 */
    suspend fun save(roomId: UUID, key: String, text: String) {
        if (text.isBlank()) {
            db.drafts().delete(roomId.toString(), key)
        } else {
            db.drafts().upsert(DraftRow(roomId.toString(), key, text, baseVersion = null, updatedAt = now()))
        }
    }

    suspend fun delete(roomId: UUID, key: String) = db.drafts().delete(roomId.toString(), key)

    /** 带基线版本的草稿（文稿）：[baseVersion] 是这段内容是在哪个版本上写的。 */
    fun observeRow(roomId: UUID, key: String): Flow<DraftRow?> = db.drafts().observe(roomId.toString(), key)

    suspend fun loadRow(roomId: UUID, key: String): DraftRow? = db.drafts().get(roomId.toString(), key)

    suspend fun saveVersioned(roomId: UUID, key: String, text: String, baseVersion: Int) =
        db.drafts().upsert(DraftRow(roomId.toString(), key, text, baseVersion, updatedAt = now()))

    companion object {
        /** 聊天输入框 */
        const val CHAT = "chat"

        /** 文稿未保存的内容 */
        fun documentKey(documentId: UUID) = "doc:$documentId"
    }
}
