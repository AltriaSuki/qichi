package app.qichi.core.database

import androidx.paging.PagingSource
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 本机所有同步实体的读写入口（`db.entities()`）。聊天的实体（消息、未读位置）在 chat_entities 表，
 * 其余在 entities 表（P17-03）：按类型分到对应的表，调用方和以前一样只说类型。
 */
class Entities(private val general: EntityDao, private val chat: ChatEntityDao) {

    suspend fun get(type: String, id: String): EntityRow? =
        if (isChat(type)) chat.get(type, id) else general.get(type, id)

    fun observe(type: String, id: String): Flow<EntityRow?> =
        if (isChat(type)) chat.observe(type, id) else general.observe(type, id)

    suspend fun upsert(row: EntityRow) =
        if (isChat(row.type)) chat.upsert(ChatEntityRow.of(row)) else general.upsert(row)

    suspend fun upsertAll(rows: List<EntityRow>) {
        val (chatRows, rest) = rows.partition { isChat(it.type) }
        if (chatRows.isNotEmpty()) chat.upsertAll(chatRows.map(ChatEntityRow::of))
        if (rest.isNotEmpty()) general.upsertAll(rest)
    }

    suspend fun delete(type: String, id: String) =
        if (isChat(type)) chat.delete(type, id) else general.delete(type, id)

    suspend fun setState(type: String, id: String, state: SyncState) =
        if (isChat(type)) chat.setState(type, id, state) else general.setState(type, id, state)

    /** 某个房间里某种实体（不含回收站里的），按 sortTime 升序。 */
    fun observeByType(roomId: String, type: String): Flow<List<EntityRow>> =
        if (isChat(type)) chat.observeByType(roomId, type) else general.observeByType(roomId, type)

    /** 包括回收站里的。 */
    fun observeAllByType(roomId: String, type: String): Flow<List<EntityRow>> =
        if (isChat(type)) chat.observeAllByType(roomId, type) else general.observeAllByType(roomId, type)

    suspend fun listByType(roomId: String, type: String): List<EntityRow> =
        if (isChat(type)) chat.listByType(roomId, type) else general.listByType(roomId, type)

    /** 某个父实体下某种实体的所有行（含回收站里的）。 */
    suspend fun byParent(type: String, parentId: String): List<EntityRow> =
        if (isChat(type)) chat.byParent(type, parentId) else general.byParent(type, parentId)

    fun observeChildren(roomId: String, type: String, parentId: String): Flow<List<EntityRow>> =
        if (isChat(type)) chat.observeChildren(roomId, type, parentId) else general.observeChildren(roomId, type, parentId)

    suspend fun children(roomId: String, type: String, parentId: String): List<EntityRow> =
        if (isChat(type)) chat.children(roomId, type, parentId) else general.children(roomId, type, parentId)

    /** 「我写下的内容」：某人在房间里各种内容各有多少（不含回收站里的）。 */
    fun observeCountsByOwner(roomId: String, ownerId: String, types: List<String>): Flow<List<TypeCount>> =
        across(types, { chat.observeCountsByOwner(roomId, ownerId, it) }, { general.observeCountsByOwner(roomId, ownerId, it) }) { a, b -> a + b }

    /** 「我写下的内容」：最近写下的几条（按时间倒序）。 */
    fun observeRecentByOwner(roomId: String, ownerId: String, types: List<String>, limit: Int): Flow<List<EntityRow>> =
        across(types, { chat.observeRecentByOwner(roomId, ownerId, it, limit) }, { general.observeRecentByOwner(roomId, ownerId, it, limit) }) { a, b ->
            (a + b).sortedByDescending { it.sortTime }.take(limit)
        }

    /** 回收站里的实体（给「我的 → 回收站」）。 */
    fun observeDeleted(roomId: String, types: List<String>): Flow<List<EntityRow>> =
        across(types, { chat.observeDeleted(roomId, it) }, { general.observeDeleted(roomId, it) }) { a, b -> a + b }

    // ── 只有聊天有的 ──

    fun observeImageMessages(roomId: String): Flow<List<EntityRow>> = chat.observeImageMessages(roomId)

    fun messagesPaging(roomId: String): PagingSource<Int, EntityRow> = chat.messagesPaging(roomId)

    fun observeNewestMessage(roomId: String): Flow<EntityRow?> = chat.observeNewestMessage(roomId)

    suspend fun countNewerMessages(roomId: String, createdSeq: Long): Int = chat.countNewerMessages(roomId, createdSeq)

    suspend fun oldestMessageSeq(roomId: String): Long? = chat.oldestMessageSeq(roomId)

    fun observeUnread(roomId: String, myUserId: String, lastReadSeq: Long): Flow<Int> = chat.observeUnread(roomId, myUserId, lastReadSeq)

    fun observeReadMarkers(roomId: String, userId: String): Flow<List<EntityRow>> = chat.observeReadMarkers(roomId, userId)

    suspend fun readMarkers(roomId: String, userId: String): List<EntityRow> = chat.readMarkers(roomId, userId)

    fun observeNewestMessageSeq(roomId: String): Flow<Long?> = chat.observeNewestMessageSeq(roomId)

    /** 退出了的房间（P16-07）：两张表里这个房间的都删掉。 */
    suspend fun deleteRoom(roomId: String) {
        general.deleteRoom(roomId)
        chat.deleteRoom(roomId)
    }

    suspend fun clear() {
        general.clear()
        chat.clear()
    }

    /** 类型分属两张表的查询：只查涉及到的表（只涉及一张时不多订一张表的变化）。 */
    private fun <T> across(
        types: List<String>,
        inChat: (List<String>) -> Flow<T>,
        inGeneral: (List<String>) -> Flow<T>,
        merge: (T, T) -> T,
    ): Flow<T> {
        val (chatTypes, rest) = types.partition(::isChat)
        return when {
            chatTypes.isEmpty() -> inGeneral(rest)
            rest.isEmpty() -> inChat(chatTypes)
            else -> combine(inChat(chatTypes), inGeneral(rest), merge)
        }
    }

    companion object {
        /** 放在 chat_entities 表里的类型（EntityType 的 wireName）。 */
        val CHAT_TYPES = setOf(EntityType.Message.wireName, EntityType.ReadMarker.wireName)

        fun isChat(type: String): Boolean = type in CHAT_TYPES
    }
}
