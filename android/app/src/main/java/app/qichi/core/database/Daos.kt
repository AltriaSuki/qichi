package app.qichi.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityDao {
    @Query("SELECT * FROM entities WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): EntityRow?

    @Query("SELECT * FROM entities WHERE type = :type AND id = :id")
    fun observe(type: String, id: String): Flow<EntityRow?>

    @Upsert
    suspend fun upsert(row: EntityRow)

    @Upsert
    suspend fun upsertAll(rows: List<EntityRow>)

    @Query("DELETE FROM entities WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("UPDATE entities SET syncState = :state WHERE type = :type AND id = :id")
    suspend fun setState(type: String, id: String, state: SyncState)

    /** 某个房间里某种实体（不含回收站里的），按 sortTime 升序。 */
    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = :type AND deleted = 0 ORDER BY sortTime, localTime")
    fun observeByType(roomId: String, type: String): Flow<List<EntityRow>>

    /** 房间里的图片消息（时间线选照片用；先按 JSON 粗筛，调用方再解开确认）。 */
    @Query(
        """SELECT * FROM entities WHERE roomId = :roomId AND type = 'message' AND deleted = 0
           AND json LIKE '%"kind":"image"%' ORDER BY sortTime DESC""",
    )
    fun observeImageMessages(roomId: String): Flow<List<EntityRow>>

    /** 包括回收站里的。 */
    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = :type ORDER BY sortTime, localTime")
    fun observeAllByType(roomId: String, type: String): Flow<List<EntityRow>>

    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = :type AND deleted = 0 ORDER BY sortTime, localTime")
    suspend fun listByType(roomId: String, type: String): List<EntityRow>

    /** 某个父实体下某种实体的所有行（含回收站里的）。 */
    @Query("SELECT * FROM entities WHERE type = :type AND parentId = :parentId")
    suspend fun byParent(type: String, parentId: String): List<EntityRow>

    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = :type AND parentId = :parentId AND deleted = 0 ORDER BY sortTime, localTime")
    fun observeChildren(roomId: String, type: String, parentId: String): Flow<List<EntityRow>>

    /**
     * 聊天列表（倒序，最新在前）：待发送的消息排在最前（按本机时间），其余按 createdSeq。
     * 回收站里的消息不显示；撤回的仍显示（显示为「谁撤回了一条消息」）。
     */
    @Query(
        """SELECT * FROM entities WHERE roomId = :roomId AND type = 'message' AND deleted = 0
           AND (sortSeq IS NULL OR sortSeq >= COALESCE((SELECT floorSeq FROM chat_history WHERE roomId = :roomId), 0))
           ORDER BY (sortSeq IS NULL) DESC, sortSeq DESC, localTime DESC""",
    )
    fun messagesPaging(roomId: String): PagingSource<Int, EntityRow>

    /** 列表最下面那条（最新的，含待发送的）：用来判断有没有新消息，不受分页窗口影响。 */
    @Query(
        """SELECT * FROM entities WHERE roomId = :roomId AND type = 'message' AND deleted = 0
           ORDER BY (sortSeq IS NULL) DESC, sortSeq DESC, localTime DESC LIMIT 1""",
    )
    fun observeNewestMessage(roomId: String): Flow<EntityRow?>

    /** 列表里排在这条之前（更新）的消息数，即它在倒序列表里的位置。待发送的消息都算更新。 */
    @Query(
        """SELECT COUNT(*) FROM entities WHERE roomId = :roomId AND type = 'message' AND deleted = 0
           AND (sortSeq IS NULL OR sortSeq > :createdSeq)""",
    )
    suspend fun countNewerMessages(roomId: String, createdSeq: Long): Int

    @Query("SELECT MIN(sortSeq) FROM entities WHERE roomId = :roomId AND type = 'message' AND sortSeq IS NOT NULL")
    suspend fun oldestMessageSeq(roomId: String): Long?

    /** 对方发的、createdSeq 大于我的已读位置、未删除的消息条数。 */
    @Query(
        """SELECT COUNT(*) FROM entities WHERE roomId = :roomId AND type = 'message' AND deleted = 0
           AND sortSeq > :lastReadSeq AND ownerId IS NOT NULL AND ownerId != :myUserId""",
    )
    fun observeUnread(roomId: String, myUserId: String, lastReadSeq: Long): Flow<Int>

    /** 某人在房间里的未读位置（本机可能暂时有两行：待发送的与服务端的，取最大）。 */
    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = 'read_marker' AND ownerId = :userId")
    fun observeReadMarkers(roomId: String, userId: String): Flow<List<EntityRow>>

    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = 'read_marker' AND ownerId = :userId")
    suspend fun readMarkers(roomId: String, userId: String): List<EntityRow>

    /** 已同步的最新一条消息的 createdSeq。 */
    @Query("SELECT MAX(sortSeq) FROM entities WHERE roomId = :roomId AND type = 'message'")
    fun observeNewestMessageSeq(roomId: String): Flow<Long?>

    /** 回收站里的实体（给「我的 → 回收站」）。 */
    @Query("SELECT * FROM entities WHERE roomId = :roomId AND deleted = 1 AND type IN (:types)")
    fun observeDeleted(roomId: String, types: List<String>): Flow<List<EntityRow>>

    @Query("SELECT * FROM entities WHERE roomId = :roomId AND type = :type AND parentId = :parentId")
    suspend fun children(roomId: String, type: String, parentId: String): List<EntityRow>

    @Query("DELETE FROM entities")
    suspend fun clear()
}

@Dao
interface ChatHistoryDao {
    @Query("SELECT floorSeq FROM chat_history WHERE roomId = :roomId")
    suspend fun floor(roomId: String): Long?

    @Upsert
    suspend fun upsert(row: ChatHistoryRow)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE roomId = :roomId")
    suspend fun get(roomId: String): SyncStateRow?

    @Query("SELECT * FROM sync_state WHERE roomId = :roomId")
    fun observe(roomId: String): Flow<SyncStateRow?>

    @Upsert
    suspend fun upsert(row: SyncStateRow)

    @Query("SELECT roomId FROM sync_state")
    suspend fun roomIds(): List<String>

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: OutboxRow): Long

    /** 下一条要发的（最早进来的待发送操作）。 */
    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY localId LIMIT 1")
    suspend fun nextPending(): OutboxRow?

    @Query("SELECT * FROM outbox ORDER BY localId")
    suspend fun all(): List<OutboxRow>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE entityType = :type AND entityId = :id AND state = 'PENDING'")
    suspend fun pendingCountFor(type: String, id: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE entityType = :type AND entityId = :id AND kind = :kind")
    fun observeCountFor(type: String, id: String, kind: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE entityType = :type AND entityId = :id AND kind = :kind")
    suspend fun countFor(type: String, id: String, kind: String): Int

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE localId = :localId")
    suspend fun recordAttempt(localId: Long, error: String?)

    /** 某个实体排队中的操作全部标记失败。 */
    @Query("UPDATE outbox SET state = 'FAILED', lastError = :error WHERE entityType = :type AND entityId = :id AND state = 'PENDING'")
    suspend fun failAllFor(type: String, id: String, error: String?)

    /** 重试：把某个实体失败的操作放回队列。 */
    @Query("UPDATE outbox SET state = 'PENDING', attempts = 0 WHERE entityType = :type AND entityId = :id AND state = 'FAILED'")
    suspend fun retryAllFor(type: String, id: String)

    @Query("DELETE FROM outbox WHERE entityType = :type AND entityId = :id")
    suspend fun deleteAllFor(type: String, id: String)

    /** 同一种可合并的操作（如推进已读位置）只保留最后一次。 */
    @Query("DELETE FROM outbox WHERE roomId = :roomId AND kind = :kind AND state = 'PENDING'")
    suspend fun deletePendingOfKind(roomId: String, kind: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE roomId = :roomId AND `key` = :key")
    suspend fun get(roomId: String, key: String): DraftRow?

    @Query("SELECT * FROM drafts WHERE roomId = :roomId AND `key` = :key")
    fun observe(roomId: String, key: String): Flow<DraftRow?>

    @Upsert
    suspend fun upsert(row: DraftRow)

    @Query("SELECT * FROM drafts WHERE roomId = :roomId AND `key` LIKE :prefix || '%'")
    fun observePrefix(roomId: String, prefix: String): Flow<List<DraftRow>>

    @Query("DELETE FROM drafts WHERE roomId = :roomId AND `key` = :key")
    suspend fun delete(roomId: String, key: String)

    @Query("DELETE FROM drafts")
    suspend fun clear()
}

@Dao
interface DocumentVersionDao {
    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version DESC")
    fun observe(documentId: String): Flow<List<DocumentVersionRow>>

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId AND version = :version")
    suspend fun get(documentId: String, version: Int): DocumentVersionRow?

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId AND version = :version")
    fun observeOne(documentId: String, version: Int): Flow<DocumentVersionRow?>

    @Upsert
    suspend fun upsert(row: DocumentVersionRow)

    /** 只补信息，不覆盖已经取到的正文。 */
    @Query(
        """INSERT INTO document_versions (id, roomId, documentId, version, baseVersion, authorId, charCount, restoredFromVersion, createdAt, body)
           VALUES (:id, :roomId, :documentId, :version, :baseVersion, :authorId, :charCount, :restoredFromVersion, :createdAt, NULL)
           ON CONFLICT(id) DO NOTHING""",
    )
    suspend fun insertInfo(
        id: String, roomId: String, documentId: String, version: Int, baseVersion: Int,
        authorId: String, charCount: Int, restoredFromVersion: Int?, createdAt: Long,
    )

    @Query("DELETE FROM document_versions WHERE documentId = :documentId")
    suspend fun deleteFor(documentId: String)
}
