package app.qichi.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本地实体的同步状态（docs/05-sync-offline.md §3.1）。 */
enum class SyncState {
    /** 与服务端一致 */
    SYNCED,

    /** 本机改过，还在发件箱里等着发出 */
    PENDING,

    /** 服务端拒绝了（4xx），界面显示「发送失败」，可重试或放弃 */
    FAILED,

    /** 基线落后（409），保留本地内容，等用户处理（重基线） */
    CONFLICT,
}

/**
 * 所有同步实体放在同一张表里：常用于查询和排序的字段做成列，完整内容以 JSON 存在 [json]
 * （结构就是 shared 里的接口数据类）。
 *
 * - [json]：界面看到的当前内容（可能含本机尚未发出的修改）
 * - [serverJson]：最近一次从服务端拿到的内容；放弃发送失败的修改时用它恢复；为空表示服务端还没有这个实体
 */
@Entity(
    tableName = "entities",
    primaryKeys = ["type", "id"],
    indices = [
        Index("roomId", "type", "sortSeq"),
        Index("roomId", "type", "sortTime"),
        Index("roomId", "type", "parentId"),
    ],
)
data class EntityRow(
    /** EntityType 的 wireName，如 message、mood_response */
    val type: String,
    val id: String,
    val roomId: String,
    /** 服务端分配的 seq；还没同步过为空 */
    val seq: Long?,
    val syncState: SyncState,
    /** 在回收站里（deletedAt 不为空） */
    val deleted: Boolean,
    /** 作者 / 创建者 / 所属用户 */
    val ownerId: String?,
    /** 父实体：子任务的 parentId、回应的 moodId、消息回复的 replyToId */
    val parentId: String?,
    /** 消息的 createdSeq（发送前为空） */
    val sortSeq: Long?,
    /** 排序用的时间（epoch 毫秒）：创建时间、日程开始时间等 */
    val sortTime: Long?,
    /** 本机写入时间（待发送的消息按它排序） */
    val localTime: Long,
    val json: String,
    val serverJson: String?,
)

/** 每个房间同步到的位置。 */
@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val roomId: String,
    val lastSeq: Long,
    /** 是否已经做过 bootstrap */
    val bootstrapped: Boolean,
    val lastSyncedAt: Long?,
)

enum class OutboxState { PENDING, FAILED }

/**
 * 发件箱：本机写操作先落到这里，由 OutboxProcessor 按 localId 顺序一次一条发给服务端。
 * [kind] 决定怎样处理响应（大多数是「响应体就是这个实体」）。
 */
@Entity(
    tableName = "outbox",
    indices = [Index("state", "localId"), Index("entityType", "entityId")],
)
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val roomId: String,
    val entityType: String,
    val entityId: String,
    val kind: String,
    val method: String,
    val path: String,
    val bodyJson: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.PENDING,
    val lastError: String? = null,
)

/** 草稿：聊天草稿、文稿未保存内容、留言草稿。只存在本机。 */
@Entity(tableName = "drafts", primaryKeys = ["roomId", "key"])
data class DraftRow(
    val roomId: String,
    val key: String,
    val text: String,
    val baseVersion: Int?,
    val updatedAt: Long,
)
