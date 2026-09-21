package app.qichi.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import app.qichi.core.auth.SessionManager
import app.qichi.core.database.ChatHistoryRow
import app.qichi.core.database.EntityRow
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/**
 * 聊天消息。列表从本机数据库分页读（Paging 3），往上翻到本机没有的部分时向服务端要更早的历史。
 * 发消息先落本机（待发送，小时钟），再经发件箱按顺序发出；同一个 id 服务端只收一次。
 */
class ChatRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    /** 消息列表，最新的在前（配合 reverseLayout 的 LazyColumn）。 */
    @OptIn(ExperimentalPagingApi::class)
    fun messages(roomId: UUID): Flow<PagingData<Local<Message>>> = Pager(
        // 占位打开：位置 0 永远是最新的一条，往上加载更早的历史时已显示的消息位置不变
        config = PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, enablePlaceholders = true),
        remoteMediator = HistoryMediator(roomId),
        pagingSourceFactory = { db.entities().messagesPaging(roomId.toString()) },
    ).flow.map { page -> page.map { LocalStore.toLocal<Message>(it) } }

    /** 最新的一条消息（含待发送的）。 */
    fun observeNewest(roomId: UUID): Flow<Local<Message>?> =
        db.entities().observeNewestMessage(roomId.toString()).map { row -> row?.let { LocalStore.toLocal<Message>(it) } }

    suspend fun sendText(roomId: UUID, text: String, replyTo: Message? = null): Message {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= Limits.MESSAGE_BODY_MAX)
        val now = clock.instant()
        val message = Message(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now,
            deletedAt = null, deletedBy = null, authorId = me, kind = MessageKind.Text, body = body, file = null,
            replyToId = replyTo?.id,
            replyAuthorId = replyTo?.authorId,
            // 服务端会按同样的规则重新填写；先在本机显示出来
            replyExcerpt = replyTo?.let { MessageRules.replyExcerpt(it.kind, it.body, it.file?.fileName, it.retractedAt != null) },
            retractedAt = null, retractedBy = null,
            createdSeq = 0,
        )
        store.writeLocal(
            roomId, message,
            OutboxOp.post(
                "rooms/$roomId/messages",
                SendMessageRequest(message.id, MessageKind.Text.wireName, body, replyToId = replyTo?.id),
            ),
        )
        scheduler.kickOutbox()
        return message
    }

    /** 发送失败的消息：重新放回发件箱。 */
    suspend fun retry(message: Message) {
        store.retry(EntityType.Message, message.id)
        scheduler.kickOutbox(now = true)
    }

    /** 发送失败的消息：不发了（本机删掉）。 */
    suspend fun abandon(message: Message) = store.abandon(EntityType.Message, message.id)

    /**
     * 往上翻历史：从本机连续历史的最早一条（chat_history.floorSeq）往前取一页。
     * 往下（更新的消息）由同步负责，不经过这里。
     */
    @OptIn(ExperimentalPagingApi::class)
    private inner class HistoryMediator(private val roomId: UUID) : RemoteMediator<Int, EntityRow>() {
        override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

        override suspend fun load(loadType: LoadType, state: PagingState<Int, EntityRow>): MediatorResult {
            if (loadType != LoadType.APPEND) return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
            return try {
                MediatorResult.Success(endOfPaginationReached = !loadOlder(roomId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediatorResult.Error(e)
            }
        }
    }

    /** 取一页更早的历史；返回服务端是否还有更早的。 */
    suspend fun loadOlder(roomId: UUID): Boolean {
        val key = roomId.toString()
        // 没有记录（旧版本升级上来）时，按本机最早一条算
        val floor = db.chatHistory().floor(key) ?: db.entities().oldestMessageSeq(key)
        if (floor == 0L) return false
        val query = if (floor == null) "" else "&beforeSeq=$floor"
        val page = api.get<MessagePage>("rooms/$roomId/messages?limit=$PAGE_SIZE$query")
        db.transaction {
            page.messages.forEach { store.applyServer(it) }
            val newFloor = if (page.hasMore) page.messages.minOfOrNull { it.createdSeq } ?: 0 else 0
            db.chatHistory().upsert(ChatHistoryRow(key, newFloor))
        }
        return page.hasMore
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
