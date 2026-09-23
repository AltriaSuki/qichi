package app.qichi.feature.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.BoardRepository
import app.qichi.core.data.DraftStore
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.BoardPost
import app.qichi.shared.api.BoardPostRevision
import app.qichi.shared.api.BoardReaction
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// ───────────────────────── 主题列表 ─────────────────────────

data class TopicSummary(val topic: Local<BoardTopic>, val postCount: Int, val lastPost: BoardPost?) {
    val lastAt: Instant get() = lastPost?.createdAt ?: topic.value.createdAt
}

data class SearchHit(val post: BoardPost, val topicTitle: String)

data class BoardListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val topics: List<TopicSummary> = emptyList(),
    val query: String = "",
    val topicHits: List<TopicSummary> = emptyList(),
    val postHits: List<SearchHit> = emptyList(),
    val loaded: Boolean = false,
) {
    val searching: Boolean get() = query.isNotBlank()
}

/** 置顶的在前（最近置顶的更前），其余按最近一条留言。 */
internal fun sortTopics(list: List<TopicSummary>): List<TopicSummary> = list.sortedWith(
    compareByDescending<TopicSummary> { it.topic.value.pinnedAt != null }
        .thenByDescending { it.topic.value.pinnedAt }
        .thenByDescending { it.lastAt },
)

@HiltViewModel(assistedFactory = BoardListViewModel.Factory::class)
class BoardListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val board: BoardRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val query = MutableStateFlow("")

    val state: StateFlow<BoardListState> = combine(people, board.observeTopics(roomId), board.observePosts(roomId), query) { p, topics, posts, q ->
        val zone = zoneOf(p.room?.timezone)
        val liveTopics = topics.associateBy { it.value.id }
        val byTopic = posts.map { it.value }.filter { it.topicId in liveTopics }.groupBy { it.topicId }
        val summaries = sortTopics(topics.map { t -> byTopic[t.value.id].orEmpty().let { TopicSummary(t, it.size, it.maxByOrNull { p -> p.createdAt }) } })
        val needle = q.trim()
        // 搜索在本机做：留言都同步在手机上，离线也能搜
        BoardListState(
            people = p, zone = zone, today = todayIn(zone), topics = summaries, query = q,
            topicHits = if (needle.isEmpty()) emptyList() else summaries.filter { it.topic.value.title.contains(needle, ignoreCase = true) },
            postHits = if (needle.isEmpty()) emptyList() else byTopic.values.flatten().filter { it.body.contains(needle, ignoreCase = true) }
                .sortedByDescending { it.createdAt }.take(50)
                .map { SearchHit(it, liveTopics[it.topicId]!!.value.title) },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardListState())

    fun search(text: String) = query.update { text.take(100) }

    fun create(title: String, body: String, onCreated: (UUID) -> Unit) = viewModelScope.launch {
        if (body.isBlank()) return@launch
        board.createTopic(roomId, title, body)?.let { onCreated(it.id) }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): BoardListViewModel
    }
}

// ───────────────────────── 主题 ─────────────────────────

data class PostItem(
    val post: Local<BoardPost>,
    /** 每种回应是谁给的 */
    val reactions: Map<BoardReactionKind, List<BoardReaction>>,
    /** 我给的回应 */
    val mine: Map<BoardReactionKind, BoardReaction>,
) {
    val conflict: Boolean get() = post.syncState == app.qichi.core.database.SyncState.CONFLICT
}

data class TopicState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    /** 还没读出来，或已经删除时为空 */
    val topic: Local<BoardTopic>? = null,
    /** 旧的在前 */
    val posts: List<PostItem> = emptyList(),
    val quote: BoardPost? = null,
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = TopicViewModel.Factory::class)
class TopicViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("topicId") private val topicId: UUID,
    private val board: BoardRepository,
    private val drafts: DraftStore,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val draftKey = "board:$topicId"
    private val composer = MutableStateFlow(Composer())

    private data class Composer(val quote: BoardPost? = null)

    val state: StateFlow<TopicState> = combine(
        people, board.observeTopics(roomId), board.observePosts(roomId), board.observeReactions(roomId), composer,
    ) { p, topics, posts, reactions, c ->
        val zone = zoneOf(p.room?.timezone)
        val byPost = reactions.map { it.value }.groupBy { it.postId }
        TopicState(
            people = p, zone = zone, today = todayIn(zone),
            topic = topics.firstOrNull { it.value.id == topicId },
            posts = posts.filter { it.value.topicId == topicId }.sortedBy { it.value.createdAt }.map { post ->
                val list = byPost[post.value.id].orEmpty()
                PostItem(post, list.groupBy { it.kind }, list.filter { it.authorId == p.myUserId }.associateBy { it.kind })
            },
            quote = c.quote?.let { q -> posts.firstOrNull { it.value.id == q.id }?.value ?: q },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TopicState())

    /** 没写完的留言存在本机，退出再回来还在。输入框自己持有内容（不经过数据库回写，快速输入不丢字）。 */
    suspend fun loadDraft(): String? = drafts.load(roomId, draftKey)

    fun onDraft(text: String) {
        viewModelScope.launch { drafts.save(roomId, draftKey, text) }
    }

    fun quote(post: BoardPost?) = composer.update { it.copy(quote = post) }

    /** 发出；成功放进发件箱时回调 [onSent]（界面清空输入框）。 */
    fun send(text: String, onSent: () -> Unit) = viewModelScope.launch {
        val topic = state.value.topic?.value ?: return@launch
        if (board.addPost(topic, text, composer.value.quote) != null) {
            composer.value = Composer()
            drafts.delete(roomId, draftKey)
            onSent()
        }
    }

    fun toggleReaction(item: PostItem, kind: BoardReactionKind) = viewModelScope.launch {
        val mine = item.mine[kind]
        if (mine != null) board.unreact(mine) else board.react(item.post.value, kind)
    }

    fun revise(post: BoardPost, body: String) = viewModelScope.launch { board.revise(post, body) }
    fun deletePost(post: BoardPost) = viewModelScope.launch { board.deletePost(post) }
    fun retry(post: BoardPost) = viewModelScope.launch { board.retry(post) }
    fun abandon(post: BoardPost) = viewModelScope.launch { board.abandon(post) }

    fun setPinned(pinned: Boolean) = viewModelScope.launch { state.value.topic?.value?.let { board.setPinned(it, pinned) } }
    fun rename(title: String) = viewModelScope.launch { state.value.topic?.value?.let { board.rename(it, title) } }
    fun deleteTopic() = viewModelScope.launch { state.value.topic?.value?.let { board.deleteTopic(it) } }

    /** 修订历史；离线或失败时为 null。 */
    suspend fun revisions(post: BoardPost): List<BoardPostRevision>? = try {
        board.revisions(post)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("topicId") topicId: UUID): TopicViewModel
    }
}
