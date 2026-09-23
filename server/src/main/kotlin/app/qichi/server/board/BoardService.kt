package app.qichi.server.board

import app.qichi.server.db.BoardPostRevisions
import app.qichi.server.db.BoardPosts
import app.qichi.server.db.BoardReactions
import app.qichi.server.db.BoardTopics
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.containsPattern
import app.qichi.server.db.ilike
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardPostRevision
import app.qichi.shared.api.BoardReaction
import app.qichi.shared.api.BoardSearchResult
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.CreateBoardPostRequest
import app.qichi.shared.api.CreateBoardTopicRequest
import app.qichi.shared.api.Patch
import app.qichi.shared.api.PutBoardReactionRequest
import app.qichi.shared.api.ReviseBoardPostRequest
import app.qichi.shared.api.UpdateBoardTopicRequest
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import app.qichi.shared.rules.BoardRules
import app.qichi.shared.rules.Limits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toBoardTopic() = BoardTopic(
    id = this[BoardTopics.id], roomId = this[BoardTopics.roomId], seq = this[BoardTopics.seq],
    createdAt = this[BoardTopics.createdAt], updatedAt = this[BoardTopics.updatedAt],
    deletedAt = this[BoardTopics.deletedAt], deletedBy = this[BoardTopics.deletedBy],
    title = this[BoardTopics.title], authorId = this[BoardTopics.authorId], pinnedAt = this[BoardTopics.pinnedAt],
)

fun ResultRow.toBoardPost() = BoardPost(
    id = this[BoardPosts.id], roomId = this[BoardPosts.roomId], seq = this[BoardPosts.seq],
    createdAt = this[BoardPosts.createdAt], updatedAt = this[BoardPosts.updatedAt],
    deletedAt = this[BoardPosts.deletedAt], deletedBy = this[BoardPosts.deletedBy],
    topicId = this[BoardPosts.topicId], authorId = this[BoardPosts.authorId], body = this[BoardPosts.body],
    quotePostId = this[BoardPosts.quotePostId], quoteAuthorId = this[BoardPosts.quoteAuthorId], quoteExcerpt = this[BoardPosts.quoteExcerpt],
    revision = this[BoardPosts.revision], revisedAt = this[BoardPosts.revisedAt],
)

fun ResultRow.toBoardReaction() = BoardReaction(
    id = this[BoardReactions.id], roomId = this[BoardReactions.roomId], seq = this[BoardReactions.seq],
    createdAt = this[BoardReactions.createdAt], updatedAt = this[BoardReactions.updatedAt],
    deletedAt = this[BoardReactions.deletedAt], deletedBy = this[BoardReactions.deletedBy],
    postId = this[BoardReactions.postId], authorId = this[BoardReactions.authorId], kind = fromWire<BoardReactionKind>(this[BoardReactions.kind]),
)

private const val SEARCH_LIMIT = 50

/**
 * 留言板（P5-03）：主题（可置顶）、留言（可引用、只有作者能修订，修订带基线）、回应（喜欢 / 拥抱 / 支持）、搜索。
 * 主题和留言两位成员都能删（进回收站）；主题删掉后，下面的留言随它隐藏。
 */
class BoardService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun topic(id: UUID): BoardTopic? = BoardTopics.selectAll().where { BoardTopics.id eq id }.singleOrNull()?.toBoardTopic()
    private fun post(id: UUID): BoardPost? = BoardPosts.selectAll().where { BoardPosts.id eq id }.singleOrNull()?.toBoardPost()
    private fun reaction(id: UUID): BoardReaction? = BoardReactions.selectAll().where { BoardReactions.id eq id }.singleOrNull()?.toBoardReaction()

    private fun liveTopic(roomId: UUID, id: UUID) = topic(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()

    /** 留言本身没删，所在主题也没删。 */
    private fun livePost(roomId: UUID, id: UUID): BoardPost {
        val p = post(id)?.takeIf { it.roomId == roomId && it.deletedAt == null } ?: notFound()
        liveTopic(roomId, p.topicId)
        return p
    }

    private fun checkTitle(raw: String): String {
        val title = raw.trim()
        validate { check(title.length in Limits.BOARD_TITLE_LENGTH, "title", "标题 1–${Limits.BOARD_TITLE_LENGTH.last} 字") }
        return title
    }

    private fun checkBody(raw: String): String {
        val body = raw.trim()
        validate { check(body.length in Limits.BOARD_POST_LENGTH, "body", "留言 1–${Limits.BOARD_POST_LENGTH.last} 字") }
        return body
    }

    // ── 主题 ──

    suspend fun topics(userId: UUID, roomId: UUID): List<BoardTopic> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        val topics = BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.map { it.toBoardTopic() }
        val lastPost = BoardPosts.select(BoardPosts.topicId, BoardPosts.createdAt)
            .where { (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() }
            .groupBy({ it[BoardPosts.topicId] }, { it[BoardPosts.createdAt] })
            .mapValues { (_, times) -> times.max() }
        topics.sortedWith(
            compareByDescending<BoardTopic> { it.pinnedAt != null }
                .thenByDescending { it.pinnedAt }
                .thenByDescending { lastPost[it.id] ?: it.createdAt },
        )
    }

    suspend fun createTopic(userId: UUID, roomId: UUID, req: CreateBoardTopicRequest): Pair<BoardTopic, Boolean> {
        val title = checkTitle(req.title)
        return db.tx {
            rooms.requireMember(roomId, userId)
            writes.create(this, roomId, userId, EntityType.BoardTopic, req.id, BoardTopics, ::topic) {
                it[BoardTopics.title] = title
                it[BoardTopics.authorId] = userId
            }
        }
    }

    suspend fun updateTopic(userId: UUID, roomId: UUID, id: UUID, req: UpdateBoardTopicRequest): BoardTopic {
        val title = (req.title as? Patch.Value)?.value?.let(::checkTitle)
        return db.tx {
            rooms.requireMember(roomId, userId)
            val current = liveTopic(roomId, id)
            val pin = (req.pinned as? Patch.Value)?.value
            val changeTitle = title != null && title != current.title
            val changePin = pin != null && pin != (current.pinnedAt != null)
            if (changeTitle || changePin) {
                writes.update(this, roomId, userId, EntityType.BoardTopic, id, BoardTopics) {
                    if (changeTitle) it[BoardTopics.title] = title!!
                    if (changePin) it[BoardTopics.pinnedAt] = if (pin == true) writes.now() else null
                }
            }
            topic(id)!!
        }
    }

    suspend fun deleteTopic(userId: UUID, roomId: UUID, id: UUID): BoardTopic = db.tx {
        rooms.requireMember(roomId, userId)
        val current = topic(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.BoardTopic, id, BoardTopics)
        topic(id)!!
    }

    // ── 留言 ──

    suspend fun createPost(userId: UUID, roomId: UUID, topicId: UUID, req: CreateBoardPostRequest): Pair<BoardPost, Boolean> {
        val body = checkBody(req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            post(req.id)?.let { existing ->
                // 重试：原样返回；换了房间、主题或作者说明 id 撞了
                if (existing.roomId != roomId || existing.topicId != topicId || existing.authorId != userId) {
                    throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
                }
                return@tx existing to false
            }
            liveTopic(roomId, topicId)
            val quoted = req.quotePostId?.let { livePost(roomId, it) }
            writes.create(this, roomId, userId, EntityType.BoardPost, req.id, BoardPosts, ::post) {
                it[BoardPosts.topicId] = topicId
                it[BoardPosts.authorId] = userId
                it[BoardPosts.body] = body
                it[BoardPosts.quotePostId] = quoted?.id
                it[BoardPosts.quoteAuthorId] = quoted?.authorId
                it[BoardPosts.quoteExcerpt] = quoted?.let { q -> BoardRules.quoteExcerpt(q.body) }
                it[BoardPosts.revision] = 1
            }
        }
    }

    suspend fun revise(userId: UUID, roomId: UUID, id: UUID, req: ReviseBoardPostRequest): BoardPost {
        val body = checkBody(req.body)
        return db.tx {
            rooms.requireMember(roomId, userId)
            RoomRepository.lockRoom(roomId)
            val current = livePost(roomId, id)
            if (current.authorId != userId) forbidden("只能修订自己的留言")
            if (req.baseRevision != current.revision) {
                throw ApiException(
                    ProblemCode.ConflictVersion, "留言已经修订过",
                    detail = "当前是第 ${current.revision} 版，你基于第 ${req.baseRevision} 版修订",
                    latestVersion = current.revision,
                )
            }
            if (body == current.body) return@tx current
            val now = writes.now()
            BoardPostRevisions.insert {
                it[postId] = id
                it[revision] = current.revision
                it[BoardPostRevisions.body] = current.body
                it[createdAt] = current.revisedAt ?: current.createdAt
            }
            writes.update(this, roomId, userId, EntityType.BoardPost, id, BoardPosts) {
                it[BoardPosts.body] = body
                it[BoardPosts.revision] = current.revision + 1
                it[BoardPosts.revisedAt] = now
            }
            post(id)!!
        }
    }

    suspend fun revisions(userId: UUID, roomId: UUID, id: UUID): List<BoardPostRevision> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        livePost(roomId, id)
        BoardPostRevisions.selectAll().where { BoardPostRevisions.postId eq id }
            .orderBy(BoardPostRevisions.revision, SortOrder.DESC)
            .map { BoardPostRevision(it[BoardPostRevisions.revision], it[BoardPostRevisions.body], it[BoardPostRevisions.createdAt]) }
    }

    suspend fun deletePost(userId: UUID, roomId: UUID, id: UUID): BoardPost = db.tx {
        rooms.requireMember(roomId, userId)
        val current = post(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.BoardPost, id, BoardPosts)
        post(id)!!
    }

    // ── 回应 ──

    suspend fun react(userId: UUID, roomId: UUID, postId: UUID, kind: BoardReactionKind, req: PutBoardReactionRequest): Pair<BoardReaction, Boolean> = db.tx {
        rooms.requireMember(roomId, userId)
        RoomRepository.lockRoom(roomId)
        reaction(req.id)?.let { existing ->
            if (existing.roomId != roomId || existing.postId != postId || existing.authorId != userId || existing.kind != kind) {
                throw ApiException(ProblemCode.ConflictId, "这个 id 已被占用")
            }
            return@tx existing to false
        }
        val target = livePost(roomId, postId)
        if (target.authorId == userId) forbidden("不能回应自己的留言")
        activeReaction(postId, userId, kind)?.let { return@tx it to false }
        writes.create(this, roomId, userId, EntityType.BoardReaction, req.id, BoardReactions, ::reaction) {
            it[BoardReactions.postId] = postId
            it[BoardReactions.authorId] = userId
            it[BoardReactions.kind] = kind.wireName
        }
    }

    suspend fun unreact(userId: UUID, roomId: UUID, postId: UUID, kind: BoardReactionKind): BoardReaction = db.tx {
        rooms.requireMember(roomId, userId)
        post(postId)?.takeIf { it.roomId == roomId } ?: notFound()
        val current = activeReaction(postId, userId, kind) ?: notFound()
        writes.softDelete(this, roomId, userId, EntityType.BoardReaction, current.id, BoardReactions)
        reaction(current.id)!!
    }

    private fun activeReaction(postId: UUID, userId: UUID, kind: BoardReactionKind): BoardReaction? =
        BoardReactions.selectAll().where {
            (BoardReactions.postId eq postId) and (BoardReactions.authorId eq userId) and
                (BoardReactions.kind eq kind.wireName) and BoardReactions.deletedAt.isNull()
        }.singleOrNull()?.toBoardReaction()

    // ── 搜索 ──

    suspend fun search(userId: UUID, roomId: UUID, rawQuery: String?): BoardSearchResult {
        val query = rawQuery?.trim().orEmpty()
        validate { check(query.length in 1..100, "q", "搜索词 1–100 字") }
        val pattern = containsPattern(query)
        return db.tx(readOnly = true) {
            rooms.requireMember(roomId, userId)
            val liveTopics = BoardTopics.selectAll().where { (BoardTopics.roomId eq roomId) and BoardTopics.deletedAt.isNull() }.map { it.toBoardTopic() }
            val topicIds = liveTopics.map { it.id }
            val matchingTopicIds = liveTopics.filter { it.title.contains(query, ignoreCase = true) }.map { it.id }
            val posts = if (topicIds.isEmpty()) emptyList() else BoardPosts.selectAll().where {
                (BoardPosts.roomId eq roomId) and BoardPosts.deletedAt.isNull() and (BoardPosts.topicId inList topicIds) and
                    (BoardPosts.body ilike pattern)
            }.orderBy(BoardPosts.createdAt, SortOrder.DESC).limit(SEARCH_LIMIT).map { it.toBoardPost() }
            BoardSearchResult(posts = posts, topics = liveTopics.filter { it.id in matchingTopicIds })
        }
    }
}
