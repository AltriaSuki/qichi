package app.qichi.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.ArchiveRepository
import app.qichi.core.data.BoardRepository
import app.qichi.core.data.DecisionRepository
import app.qichi.core.data.DocumentRepository
import app.qichi.core.data.EventRepository
import app.qichi.core.data.HubOrderStore
import app.qichi.core.data.IdeaRepository
import app.qichi.core.data.MoodRepository
import app.qichi.core.data.People
import app.qichi.core.data.PlanRepository
import app.qichi.core.data.QnaRepository
import app.qichi.core.data.ReadingRepository
import app.qichi.core.data.ReviewRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.SummaryRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.zoneOf
import app.qichi.navigation.Page
import app.qichi.navigation.TogetherGroup
import app.qichi.shared.api.BoardTopic
import app.qichi.shared.api.Document
import app.qichi.shared.api.ReadingProgress
import app.qichi.shared.api.Summary
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecentCreations(val document: Document? = null, val topic: BoardTopic? = null)

/** 「一起」目录页：目录右侧的数字，底部的快速记灵感。 */
@HiltViewModel(assistedFactory = TogetherHubViewModel.Factory::class)
class TogetherHubViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val ideas: IdeaRepository,
    rooms: RoomRepository,
    moods: MoodRepository,
    qna: QnaRepository,
    plans: PlanRepository,
    todos: TodoRepository,
    events: EventRepository,
    decisions: DecisionRepository,
    documents: DocumentRepository,
    board: BoardRepository,
    archive: ArchiveRepository,
    reviews: ReviewRepository,
    summaries: SummaryRepository,
    reading: ReadingRepository,
    session: SessionManager,
    private val hubOrder: HubOrderStore,
) : ViewModel() {
    /** 每组功能的先后（P16-09）：自己排过的按自己的，没排过的按默认，新加的功能排在最后 */
    val order: StateFlow<Map<TogetherGroup, List<Page>>> = hubOrder.order.map { o ->
        TogetherGroup.entries.associateWith { g ->
            val defaults = Page.inGroup(g)
            arrange(defaults.map { it.slug }, o.orders[g.name]).mapNotNull { slug -> defaults.firstOrNull { it.slug == slug } }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TogetherGroup.entries.associateWith { Page.inGroup(it) })

    /** 从「一起」点开一个功能：记一次，「按我最常用的排」用 */
    fun recordOpen(page: Page) = hubOrder.recordUse(page.slug)

    fun move(group: TogetherGroup, page: Page, delta: Int) =
        hubOrder.setOrder(group.name, app.qichi.feature.together.move(slugs(group), page.slug, delta))

    fun sortByUsage(group: TogetherGroup) =
        hubOrder.setOrder(group.name, byUsage(slugs(group), hubOrder.order.value.uses))

    fun resetOrder(group: TogetherGroup) = hubOrder.resetOrder(group.name)

    private fun slugs(group: TogetherGroup): List<String> = order.value[group].orEmpty().map { it.slug }

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 记下了一条：界面给一句轻提示 */
    val saved: SharedFlow<Unit> = _saved

    private val minuteTicker = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    private val lists = combine(
        moods.observeMoods(roomId), qna.observeRounds(roomId), plans.observePlans(roomId),
        todos.observeTodos(roomId), events.observeEvents(roomId),
    ) { m, r, p, t, e -> Lists(m.map { it.value }, r.map { it.value }, p.map { it.value }, t.map { it.value }, e.map { it.value }) }

    private data class Lists(
        val moods: List<app.qichi.shared.api.Mood>,
        val rounds: List<app.qichi.shared.api.QnaRound>,
        val plans: List<app.qichi.shared.api.Plan>,
        val todos: List<app.qichi.shared.api.Todo>,
        val events: List<app.qichi.shared.api.Event>,
    )

    private data class Look(
        val documents: List<Document>,
        val topics: List<BoardTopic>,
        val archiveItems: Int,
        val reviews: Int,
        val summaries: List<Summary>,
        val progress: List<ReadingProgress>,
    )

    private val look = combine(
        documents.observeDocuments(roomId), board.observeTopics(roomId), archive.observeItems(roomId),
        combine(reviews.observeDocuments(roomId), summaries.observeSummaries(roomId)) { r, sm -> r to sm },
        reading.observeProgress(roomId),
    ) { docs, topics, items, (r, sm), p ->
        Look(
            docs.map { it.value }.filter { it.deletedAt == null }, topics.map { it.value }.filter { it.deletedAt == null },
            items.count { it.value.deletedAt == null }, r.count { it.deletedAt == null }, sm.map { it.value }.filter { it.deletedAt == null }, p,
        )
    }

    val counts: StateFlow<Map<Page, String>> = combine(
        rooms.observeRoom(roomId), lists, combine(ideas.observeIdeas(roomId), decisions.observeDecisions(roomId)) { i, d -> i to d }, look, minuteTicker,
    ) { room, l, (allIdeas, allDecisions), lk, now ->
        hubCounts(
            HubData(
                session.currentUserId, zoneOf(room?.timezone), now, l.moods, l.rounds, l.plans, l.todos, l.events,
                allIdeas.map { it.value }, allDecisions.map { it.value },
                documents = lk.documents.size, topics = lk.topics.size, archiveItems = lk.archiveItems, reviews = lk.reviews,
                summaries = lk.summaries, progress = lk.progress,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 顶栏右边的两人标记、「最近」卡片上的名字 */
    val people: StateFlow<People> = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members ->
        People(room, members, session.currentUserId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), People.Empty)

    /** 「创作」下面的「最近」：最近改过的一篇文稿、最近的一个留言主题 */
    val recent: StateFlow<RecentCreations> = look.map { lk ->
        RecentCreations(lk.documents.maxByOrNull { it.updatedAt }, lk.topics.maxByOrNull { it.updatedAt })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentCreations())

    fun addIdea(text: String) = viewModelScope.launch {
        if (ideas.add(roomId, text) != null) _saved.tryEmit(Unit)
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TogetherHubViewModel
    }
}
