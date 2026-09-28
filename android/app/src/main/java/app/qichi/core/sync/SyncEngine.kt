package app.qichi.core.sync

import app.qichi.core.database.QichiDatabase
import app.qichi.core.database.ChatHistoryRow
import app.qichi.core.database.SyncStateRow
import app.qichi.core.network.ApiClient
import app.qichi.core.network.ApiException
import app.qichi.core.network.get
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.Lenient
import app.qichi.shared.api.SyncEntity
import app.qichi.shared.model.ChangeOp
import app.qichi.shared.rules.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.UUID

/** 最近一次拉取没成功：哪个房间、原因、什么时候（epoch 毫秒）。 */
data class SyncProblem(val roomId: UUID, val message: String, val at: Long)

/**
 * 拉取（docs/05-sync-offline.md §3.2）：
 * 第一次（或清除数据后）走 bootstrap；之后循环 `sync?since=lastSeq`，每页在一个 Room 事务里写入并推进 lastSeq。
 * 同一时间只允许一个拉取在跑。
 *
 * 服务端比 App 新时（P13-07）：逐条解码，认不出来的跳过、同步位置照常前进，并记进 [unknown]；
 * App 升级后发现有旧版本跳过的内容，就对那个房间重新快照。
 */
class SyncEngine(
    private val api: ApiClient,
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val unknown: UnknownContent = UnknownContent(UnknownContent.MemoryStore(), currentVersion = 0),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    private val _problem = MutableStateFlow<SyncProblem?>(null)

    /** 是否正在拉取（下拉刷新的指示用）。 */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** 最近一次拉取出的问题（P13-10）：各处调用方都把拉取失败吞掉了，界面靠它知道「同步出了问题」；那个房间拉取成功就清掉。 */
    val problem: StateFlow<SyncProblem?> = _problem.asStateFlow()

    suspend fun lastSeq(roomId: UUID): Long = db.syncState().get(roomId.toString())?.lastSeq ?: 0

    /** 拉取一个房间到最新。网络错误向上抛出（调用方决定是否稍后再试）。 */
    suspend fun pull(roomId: UUID) = mutex.withLock { pullLocked(roomId) }

    /**
     * 只在本地 lastSeq 落后于 [seq] 时拉取（WebSocket 收到 changed、hello 时用）。
     * 排到锁以后再比较（P17-06）：回到前台时 hello 和「全部拉一遍」同时来，后到的那个排队时前一个已经拉完了，不用再发一次请求。
     */
    suspend fun pullIfBehind(roomId: UUID, seq: Long) = mutex.withLock {
        if (seq > lastSeq(roomId)) pullLocked(roomId)
    }

    private suspend fun pullLocked(roomId: UUID) {
        _syncing.value = true
        try {
            val state = db.syncState().get(roomId.toString())
            if (state == null || !state.bootstrapped || unknown.needsRebootstrap(roomId)) {
                bootstrap(roomId)
            } else {
                pullChanges(roomId, state.lastSeq)
            }
            _problem.update { if (it?.roomId == roomId) null else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 断网、超时、登录失效：是常态或另有提示，不算「同步出了问题」
            throw e
        } catch (e: Exception) {
            _problem.value = SyncProblem(roomId, describe(e), now())
            throw e
        } finally {
            _syncing.value = false
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> "${e.userMessage}（${e.status}）"
        is SerializationException -> "收到的数据这个版本读不懂"
        else -> e.message ?: e::class.simpleName ?: "未知错误"
    }

    private suspend fun bootstrap(roomId: UUID) {
        val json = api.get<JsonElement>("rooms/$roomId/bootstrap")
        val decoded = Lenient.container(Bootstrap.serializer(), json)
        val snapshot = decoded.value
        // 先记下再写入：写入后同步位置就前进了，不能漏记
        if (decoded.incomplete) unknown.mark(roomId)
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
                addAll(snapshot.docComments)
                addAll(snapshot.messages)
            }
            all.forEach { store.applyServer(it) }
            db.syncState().upsert(SyncStateRow(roomId.toString(), snapshot.lastSeq, bootstrapped = true, lastSyncedAt = now()))
            // 最近 50 条之前还有没有：没有就说明历史已经完整（按原始数据算，跳过的消息也算上）
            val floor = if (snapshot.hasMoreMessages) Lenient.rawMin(json, "messages", "createdSeq") ?: 0 else 0
            db.chatHistory().upsert(ChatHistoryRow(roomId.toString(), floor))
        }
        unknown.rebootstrapped(roomId, decoded.incomplete)
        // bootstrap 与 sync 之间可能又有变化：接着补一次
        pullChanges(roomId, snapshot.lastSeq)
    }

    private suspend fun pullChanges(roomId: UUID, from: Long) {
        var since = from
        do {
            val decoded = Lenient.syncPage(api.get<JsonElement>("rooms/$roomId/sync?since=$since&limit=${Limits.SYNC_PAGE_DEFAULT}"))
            val page = decoded.value
            if (decoded.incomplete) unknown.mark(roomId)
            db.transaction {
                for (change in page.changes) {
                    when (change.op) {
                        ChangeOp.Upsert -> store.applyServer(change.entity as SyncEntity)
                        ChangeOp.Delete -> store.applyServerDelete(change.type, change.id)
                    }
                }
                db.syncState().upsert(SyncStateRow(roomId.toString(), page.toSeq, bootstrapped = true, lastSyncedAt = now()))
            }
            since = page.toSeq
        } while (page.hasMore)
    }
}
