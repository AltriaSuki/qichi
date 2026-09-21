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
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.Message
import app.qichi.shared.api.MessagePage
import app.qichi.shared.api.MessageSearchPage
import app.qichi.shared.api.ReadMarker
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.FileKind
import app.qichi.shared.model.MessageKind
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.Limits
import app.qichi.shared.rules.MessageRules
import app.qichi.shared.util.UuidV7
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.File
import java.net.URLEncoder
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

    /** 我读到哪条了（createdSeq；还没读过为 0）。 */
    fun observeLastRead(roomId: UUID): Flow<Long> =
        db.entities().observeReadMarkers(roomId.toString(), me.toString())
            .map { rows -> rows.maxOfOrNull { LocalStore.toLocal<ReadMarker>(it).value.lastReadSeq } ?: 0L }
            .distinctUntilChanged()

    /** 未读数：对方发的、在我的未读位置之后、没删除的消息。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeUnread(roomId: UUID): Flow<Int> =
        observeLastRead(roomId).flatMapLatest { last -> db.entities().observeUnread(roomId.toString(), me.toString(), last) }

    /** 已同步的最新一条消息的 createdSeq。 */
    fun observeNewestSeq(roomId: UUID): Flow<Long> =
        db.entities().observeNewestMessageSeq(roomId.toString()).map { it ?: 0L }.distinctUntilChanged()

    /**
     * 推进自己的未读位置（只进不退）。本机立即生效，经发件箱发出；连续推进只保留最后一次请求。
     * 只同步到自己的其他设备，对方看不到（不做已读回执）。
     */
    suspend fun markRead(roomId: UUID, createdSeq: Long) {
        val mine = db.entities().readMarkers(roomId.toString(), me.toString()).map { LocalStore.toLocal<ReadMarker>(it).value }
        val current = mine.maxByOrNull { it.lastReadSeq }
        if (createdSeq <= (current?.lastReadSeq ?: 0L)) return
        val now = clock.instant()
        // 第一次推进时本机还没有这一行：先用临时 id，服务端的响应回来后换成真的
        val marker = (current ?: ReadMarker(UuidV7.generate(), roomId, 0, now, now, null, null, me, 0))
            .copy(lastReadSeq = createdSeq, updatedAt = now)
        store.writeLocal(
            roomId, marker,
            OutboxOp.put("rooms/$roomId/read-marker", UpdateReadMarkerRequest(createdSeq), kind = OutboxOp.KIND_READ_MARKER),
            coalesce = true,
        )
        scheduler.kickOutbox()
    }

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

    /**
     * 上传附件（必须在线）。[fileId] 由调用方生成并在重试时沿用，服务端按它去重。
     */
    suspend fun upload(roomId: UUID, fileId: UUID, attachment: PreparedAttachment, onProgress: (Float) -> Unit): FileMeta {
        val form = MultiPartFormDataContent(
            formData {
                append("kind", attachment.kind.wireName)
                append("id", fileId.toString())
                append(
                    "file",
                    ChannelProvider(attachment.sizeBytes) { attachment.open().toByteReadChannel() },
                    Headers.build {
                        append(HttpHeaders.ContentType, attachment.mimeType)
                        append(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, attachment.fileName).toString(),
                        )
                    },
                )
            },
        )
        return api.upload("rooms/$roomId/files", form, FileMeta.serializer()) { sent, total ->
            val all = total ?: attachment.sizeBytes
            if (all > 0) onProgress((sent.toFloat() / all).coerceIn(0f, 1f))
        }
    }

    /** 带着已上传的文件发一条图片或文件消息（之后和文字消息一样走发件箱）。 */
    suspend fun sendAttachment(roomId: UUID, file: FileMeta, replyTo: Message? = null): Message {
        val kind = if (file.kind == FileKind.Image) MessageKind.Image else MessageKind.File
        val now = clock.instant()
        val message = Message(
            id = UuidV7.generate(), roomId = roomId, seq = 0, createdAt = now, updatedAt = now,
            deletedAt = null, deletedBy = null, authorId = me, kind = kind, body = "", file = file,
            replyToId = replyTo?.id,
            replyAuthorId = replyTo?.authorId,
            replyExcerpt = replyTo?.let { MessageRules.replyExcerpt(it.kind, it.body, it.file?.fileName, it.retractedAt != null) },
            retractedAt = null, retractedBy = null,
            createdSeq = 0,
        )
        store.writeLocal(
            roomId, message,
            OutboxOp.post("rooms/$roomId/messages", SendMessageRequest(message.id, kind.wireName, fileId = file.id, replyToId = replyTo?.id)),
        )
        scheduler.kickOutbox()
        return message
    }

    /** 下载文件到本机缓存（已经下载过就直接用），返回本地文件。 */
    suspend fun download(file: FileMeta, dir: File, onProgress: (Float) -> Unit): File {
        val target = File(File(dir, file.id.toString()).apply { mkdirs() }, safeFileName(file.fileName))
        if (target.exists() && target.length() == file.sizeBytes) return target
        api.download("files/${file.id}", target) { received, total ->
            val all = total ?: file.sizeBytes
            if (all > 0) onProgress((received.toFloat() / all).coerceIn(0f, 1f))
        }
        return target
    }

    /** 撤回自己发的消息：本机立即显示「你撤回了一条消息」，经发件箱发出。 */
    suspend fun retract(message: Message) {
        val now = clock.instant()
        store.writeLocal(
            message.roomId,
            message.copy(body = "", file = null, retractedAt = now, retractedBy = me, updatedAt = now),
            OutboxOp.action("rooms/${message.roomId}/messages/${message.id}/retract"),
        )
        scheduler.kickOutbox()
    }

    /** 删除进回收站：本机立即从列表消失。 */
    suspend fun delete(message: Message) {
        val now = clock.instant()
        store.writeLocal(
            message.roomId,
            message.copy(deletedAt = now, deletedBy = me, updatedAt = now),
            OutboxOp.delete("rooms/${message.roomId}/messages/${message.id}"),
        )
        scheduler.kickOutbox()
    }

    /** 搜索（服务端，需要联网）：不含撤回、删除的，最新的在前。 */
    suspend fun search(roomId: UUID, query: String, cursor: String?): MessageSearchPage {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val after = cursor?.let { "&cursor=" + URLEncoder.encode(it, Charsets.UTF_8) }.orEmpty()
        return api.get("rooms/$roomId/messages/search?q=$q&limit=${Limits.CURSOR_PAGE_DEFAULT}$after")
    }

    /** 发送失败的消息：重新放回发件箱。 */
    suspend fun retry(message: Message) {
        store.retry(EntityType.Message, message.id)
        scheduler.kickOutbox(now = true)
    }

    /** 发送失败的消息：不发了（本机删掉）。 */
    suspend fun abandon(message: Message) = store.abandon(EntityType.Message, message.id)

    /** 跳到某条消息的结果。 */
    sealed interface Position {
        /** 在倒序列表里的位置（0 = 最新） */
        data class Found(val index: Int) : Position
        data object Deleted : Position
        data object Missing : Position
    }

    /**
     * 找到一条消息在列表里的位置；本机没有时一页一页往上取历史直到找到（最多 [MAX_JUMP_PAGES] 页）。
     */
    suspend fun positionOf(roomId: UUID, messageId: UUID): Position {
        repeat(MAX_JUMP_PAGES + 1) { attempt ->
            visiblePosition(roomId, messageId)?.let { return it }
            // 到头了（或翻得太多）：最后一页可能正好带回了它
            if (attempt == MAX_JUMP_PAGES || !loadOlder(roomId)) return visiblePosition(roomId, messageId) ?: Position.Missing
        }
        return Position.Missing
    }

    /** 本机有、且在连续历史之内（列表里看得到）时返回位置；否则 null。 */
    private suspend fun visiblePosition(roomId: UUID, messageId: UUID): Position? {
        val row = db.entities().get(EntityType.Message.wireName, messageId.toString()) ?: return null
        val seq = row.sortSeq
        val floor = db.chatHistory().floor(roomId.toString()) ?: 0
        if (seq != null && seq < floor) return null
        if (row.deleted) return Position.Deleted
        // 待发送的消息都在最下面
        return Position.Found(if (seq == null) 0 else db.entities().countNewerMessages(roomId.toString(), seq))
    }

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

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "file" }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_JUMP_PAGES = 200
    }
}
