package app.qichi.core.sync

import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.ChatHistoryRow
import app.qichi.core.database.SyncStateRow
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.EntityCodec
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 拉取（docs/05-sync-offline.md §3.2）：
 * 第一次（或清除数据后）走 bootstrap；之后循环 `sync?since=lastSeq`，每页在一个 Room 事务里写入并推进 lastSeq。
 * 同一时间只允许一个拉取在跑。
 */
class SyncEngine(
    private val api: ApiClient,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)

    /** 是否正在拉取（下拉刷新的指示用）。 */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    suspend fun lastSeq(roomId: UUID): Long = db.syncState().get(roomId.toString())?.lastSeq ?: 0

    /** 拉取一个房间到最新。网络错误向上抛出（调用方决定是否稍后再试）。 */
    suspend fun pull(roomId: UUID) = mutex.withLock {
        _syncing.value = true
        try {
            val state = db.syncState().get(roomId.toString())
            if (state == null || !state.bootstrapped) {
                bootstrap(roomId)
            } else {
                pullChanges(roomId, state.lastSeq)
            }
        } finally {
            _syncing.value = false
        }
    }

    /** 只在本地 lastSeq 落后于 [seq] 时拉取（WebSocket 收到 changed 时用）。 */
    suspend fun pullIfBehind(roomId: UUID, seq: Long) {
        if (seq > lastSeq(roomId)) pull(roomId)
    }

    private suspend fun bootstrap(roomId: UUID) {
        val snapshot = api.get<Bootstrap>("rooms/$roomId/bootstrap")
        db.transaction {
            val all: List<SyncEntity> = buildList {
                add(snapshot.room)
                addAll(snapshot.members)
                snapshot.readMarker?.let(::add)
                addAll(snapshot.moods)
                addAll(snapshot.moodReplies)
                addAll(snapshot.todos)
                addAll(snapshot.events)
                addAll(snapshot.questions)
                addAll(snapshot.qnaRounds)
                addAll(snapshot.answers)
                addAll(snapshot.plans)
                addAll(snapshot.planStages)
                addAll(snapshot.milestones)
                addAll(snapshot.planLogs)
                addAll(snapshot.ideas)
                addAll(snapshot.documents)
                addAll(snapshot.boardTopics)
                addAll(snapshot.boardPosts)
                addAll(snapshot.boardReactions)
                addAll(snapshot.archiveItems)
                addAll(snapshot.decisions)
                addAll(snapshot.books)
                addAll(snapshot.readingProgress)
                addAll(snapshot.highlights)
                addAll(snapshot.summaries)
                addAll(snapshot.reviewDocuments)
                addAll(snapshot.reviewVersions)
                addAll(snapshot.annotations)
                addAll(snapshot.annotationReplies)
                addAll(snapshot.aiFindings)
                addAll(snapshot.aiActions)
                addAll(snapshot.messages)
            }
            all.forEach { store.applyServer(it) }
            db.syncState().upsert(SyncStateRow(roomId.toString(), snapshot.lastSeq, bootstrapped = true, lastSyncedAt = now()))
            // 最近 50 条之前还有没有：没有就说明历史已经完整
            val floor = if (snapshot.hasMoreMessages) snapshot.messages.minOfOrNull { it.createdSeq } ?: 0 else 0
            db.chatHistory().upsert(ChatHistoryRow(roomId.toString(), floor))
        }
        // bootstrap 与 sync 之间可能又有变化：接着补一次
        pullChanges(roomId, snapshot.lastSeq)
    }

    private suspend fun pullChanges(roomId: UUID, from: Long) {
        var since = from
        do {
            val page = api.get<SyncResponse>("rooms/$roomId/sync?since=$since&limit=${Limits.SYNC_PAGE_DEFAULT}")
            db.transaction {
                for (change in page.changes) {
                    when (change.op) {
                        ChangeOp.Upsert -> store.applyServer(EntityCodec.decode(change.type, change.data!!) as SyncEntity)
                        ChangeOp.Delete -> store.applyServerDelete(change.type, change.id)
                    }
                }
                db.syncState().upsert(SyncStateRow(roomId.toString(), page.toSeq, bootstrapped = true, lastSyncedAt = now()))
            }
            since = page.toSeq
        } while (page.hasMore)
    }
}
