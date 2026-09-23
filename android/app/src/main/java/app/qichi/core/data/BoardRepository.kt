package app.qichi.core.data

import app.qichi.core.auth.SessionManager
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.core.sync.Local
import app.qichi.core.sync.LocalStore
import app.qichi.core.sync.OutboxOp
import app.qichi.core.sync.SyncScheduler
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardPostRevision
import app.qichi.shared.api.BoardReaction
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.CreateBoardPostRequest
import app.qichi.shared.api.CreateBoardTopicRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.PutBoardReactionRequest
import app.qichi.shared.api.ReviseBoardPostRequest
import app.qichi.shared.api.UpdateBoardTopicRequest
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.BoardRules
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/**
 * 留言板：主题、留言、回应都是同步实体，先写本机再经发件箱发出，断网时也能写、联网补发。
 * 修订带基线（当前修订号）；另一台手机先改过时服务端 409，本机内容保留（CONFLICT），由自己决定放弃。
 */
class BoardRepository(
    private val db: QichiDatabase,
    private val store: LocalStore,
    private val api: ApiClient,
    private val scheduler: SyncScheduler,
    private val session: SessionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val me: UUID get() = session.currentUserId ?: error("未登录")

    private inline fun <reified T : app.qichi.shared.api.SyncEntity> observe(roomId: UUID, type: EntityType): Flow<List<Local<T>>> =
        db.entities().observeByType(roomId.toString(), type.wireName).map { rows -> rows.map { LocalStore.toLocal<T>(it) } }

    fun observeTopics(roomId: UUID): Flow<List<Local<BoardTopic>>> = observe(roomId, EntityType.BoardTopic)
    fun observePosts(roomId: UUID): Flow<List<Local<BoardPost>>> = observe(roomId, EntityType.BoardPost)
    fun observeReactions(roomId: UUID): Flow<List<Local<BoardReaction>>> = observe(roomId, EntityType.BoardReaction)

    // ── 主题 ──

    /** 新主题和第一条留言一起写（发件箱里按顺序先建主题再发留言）。 */
    suspend fun createTopic(roomId: UUID, rawTitle: String, rawBody: String): BoardTopic? {
        val title = rawTitle.trim().take(Limits.BOARD_TITLE_LENGTH.last).ifEmpty { return null }
        val now = clock.instant()
        val topic = BoardTopic(UuidV7.generate(), roomId, 0, now, now, null, null, title, me, null)
        store.writeLocal(roomId, topic, OutboxOp.post("rooms/$roomId/board/topics", CreateBoardTopicRequest(topic.id, title)))
        addPost(topic, rawBody, quote = null, kick = false)
        scheduler.kickOutbox()
        return topic
    }

    suspend fun rename(topic: BoardTopic, rawTitle: String) {
        val title = rawTitle.trim().take(Limits.BOARD_TITLE_LENGTH.last)
        if (title.isEmpty() || title == topic.title) return
        store.writeLocal(topic.roomId, topic.copy(title = title, updatedAt = clock.instant()),
            OutboxOp.patch("rooms/${topic.roomId}/board/topics/${topic.id}", UpdateBoardTopicRequest(title = Patch.of(title))))
        scheduler.kickOutbox()
    }

    suspend fun setPinned(topic: BoardTopic, pinned: Boolean) {
        if (pinned == (topic.pinnedAt != null)) return
        val now = clock.instant()
        store.writeLocal(topic.roomId, topic.copy(pinnedAt = if (pinned) now else null, updatedAt = now),
            OutboxOp.patch("rooms/${topic.roomId}/board/topics/${topic.id}", UpdateBoardTopicRequest(pinned = Patch.of(pinned))))
        scheduler.kickOutbox()
    }

    suspend fun deleteTopic(topic: BoardTopic) {
        store.writeLocal(topic.roomId, topic.copy(deletedAt = clock.instant(), deletedBy = me),
            OutboxOp.delete("rooms/${topic.roomId}/board/topics/${topic.id}"))
        scheduler.kickOutbox()
    }

    // ── 留言 ──

    /** 发一条留言；[quote] 是引用的留言，摘录在本机先按同样的规则生成。空白不发。 */
    suspend fun addPost(topic: BoardTopic, rawBody: String, quote: BoardPost?, kick: Boolean = true): BoardPost? {
        val body = rawBody.trim().take(Limits.BOARD_POST_LENGTH.last).ifEmpty { return null }
        val now = clock.instant()
        val post = BoardPost(
            id = UuidV7.generate(), roomId = topic.roomId, seq = 0, createdAt = now, updatedAt = now, deletedAt = null, deletedBy = null,
            topicId = topic.id, authorId = me, body = body,
            quotePostId = quote?.id, quoteAuthorId = quote?.authorId, quoteExcerpt = quote?.let { BoardRules.quoteExcerpt(it.body) },
            revision = 1, revisedAt = null,
        )
        store.writeLocal(topic.roomId, post,
            OutboxOp.post("rooms/${topic.roomId}/board/topics/${topic.id}/posts", CreateBoardPostRequest(post.id, body, quote?.id)))
        if (kick) scheduler.kickOutbox()
        return post
    }

    /** 修订自己的留言：基线是当前修订号；本机先显示新内容。 */
    suspend fun revise(post: BoardPost, rawBody: String) {
        val body = rawBody.trim().take(Limits.BOARD_POST_LENGTH.last)
        if (body.isEmpty() || body == post.body) return
        val now = clock.instant()
        store.writeLocal(post.roomId, post.copy(body = body, revision = post.revision + 1, revisedAt = now, updatedAt = now),
            OutboxOp.patch("rooms/${post.roomId}/board/posts/${post.id}", ReviseBoardPostRequest(post.revision, body)))
        scheduler.kickOutbox()
    }

    suspend fun deletePost(post: BoardPost) {
        store.writeLocal(post.roomId, post.copy(deletedAt = clock.instant(), deletedBy = me),
            OutboxOp.delete("rooms/${post.roomId}/board/posts/${post.id}"))
        scheduler.kickOutbox()
    }

    suspend fun retry(post: BoardPost) {
        store.retry(EntityType.BoardPost, post.id)
        scheduler.kickOutbox()
    }

    /** 放弃发不出去（或修订冲突）的内容：回到服务端的样子，服务端没有就删掉。 */
    suspend fun abandon(post: BoardPost) = store.abandon(EntityType.BoardPost, post.id)

    /** 修订历史（在线取，旧的版本不进同步）。 */
    suspend fun revisions(post: BoardPost): List<BoardPostRevision> =
        api.get("rooms/${post.roomId}/board/posts/${post.id}/revisions")

    // ── 回应 ──

    suspend fun react(post: BoardPost, kind: BoardReactionKind) {
        val now = clock.instant()
        val reaction = BoardReaction(UuidV7.generate(), post.roomId, 0, now, now, null, null, post.id, me, kind)
        store.writeLocal(post.roomId, reaction,
            OutboxOp.put("rooms/${post.roomId}/board/posts/${post.id}/reactions/${kind.wireName}", PutBoardReactionRequest(reaction.id)))
        scheduler.kickOutbox()
    }

    suspend fun unreact(reaction: BoardReaction) {
        store.writeLocal(reaction.roomId, reaction.copy(deletedAt = clock.instant(), deletedBy = me),
            OutboxOp.delete("rooms/${reaction.roomId}/board/posts/${reaction.postId}/reactions/${reaction.kind.wireName}"))
        scheduler.kickOutbox()
    }
}
