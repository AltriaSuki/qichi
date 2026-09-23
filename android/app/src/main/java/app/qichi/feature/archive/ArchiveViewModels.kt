package app.qichi.feature.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.ArchiveRepository
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.ArchiveItem
import app.qichi.shared.api.ArchiveRevision
import app.qichi.shared.api.Message
import app.qichi.shared.model.ArchiveKind
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal val ArchiveKind.label: String
    get() = when (this) {
        ArchiveKind.Preference -> "偏好"
        ArchiveKind.Consensus -> "共识"
        ArchiveKind.Decision -> "决定"
        ArchiveKind.Boundary -> "边界"
        ArchiveKind.Concern -> "担忧"
        ArchiveKind.Milestone -> "里程碑"
    }

data class ArchiveListState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val items: List<Local<ArchiveItem>> = emptyList(),
    /** 为空表示全部 */
    val filter: ArchiveKind? = null,
    val loaded: Boolean = false,
) {
    val shown: List<Local<ArchiveItem>> get() = if (filter == null) items else items.filter { it.value.kind == filter }
}

@HiltViewModel(assistedFactory = ArchiveListViewModel.Factory::class)
class ArchiveListViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val archive: ArchiveRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val filter = MutableStateFlow<ArchiveKind?>(null)

    val state: StateFlow<ArchiveListState> = combine(people, archive.observeItems(roomId), filter) { p, items, f ->
        val zone = zoneOf(p.room?.timezone)
        ArchiveListState(p, zone, todayIn(zone), items, f, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveListState())

    fun filter(kind: ArchiveKind?) { filter.value = kind }

    /** 从聊天「存进档案」进来：取出那条消息，用来预填。 */
    suspend fun message(id: UUID): Message? = archive.message(id)

    fun create(kind: ArchiveKind, title: String, body: String, sourceMessageId: UUID?, onCreated: (UUID) -> Unit) = viewModelScope.launch {
        archive.create(roomId, kind, title, body, sourceMessageId)?.let { onCreated(it.id) }
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): ArchiveListViewModel
    }
}

data class ArchiveDetailState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    /** 还没读出来，或已经删除时为空 */
    val item: Local<ArchiveItem>? = null,
    /** 来源消息（本机有才显示内容） */
    val source: Message? = null,
    val loaded: Boolean = false,
) {
    val conflict: Boolean get() = item?.syncState == SyncState.CONFLICT
}

@HiltViewModel(assistedFactory = ArchiveDetailViewModel.Factory::class)
class ArchiveDetailViewModel @AssistedInject constructor(
    @Assisted("roomId") private val roomId: UUID,
    @Assisted("itemId") private val itemId: UUID,
    private val archive: ArchiveRepository,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }

    val state: StateFlow<ArchiveDetailState> = combine(people, archive.observeItems(roomId)) { p, items ->
        val zone = zoneOf(p.room?.timezone)
        val item = items.firstOrNull { it.value.id == itemId }
        ArchiveDetailState(p, zone, todayIn(zone), item, item?.value?.sourceMessageId?.let { archive.message(it) }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveDetailState())

    private val _revisions = MutableStateFlow<List<ArchiveRevision>?>(null)
    /** 历次修订；为空表示还没取到（离线） */
    val revisions: StateFlow<List<ArchiveRevision>?> = _revisions

    /** 在线取历次修订；条目的修订号变了（自己或对方修订了）再取一次。 */
    fun loadRevisions() = viewModelScope.launch {
        val item = state.value.item?.value ?: return@launch
        try {
            _revisions.value = archive.revisions(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun revise(title: String, body: String, sourceMessageId: UUID?) = viewModelScope.launch {
        state.value.item?.value?.let { archive.revise(it, title, body, sourceMessageId) }
    }

    fun delete() = viewModelScope.launch { state.value.item?.value?.let { archive.delete(it) } }
    fun retry() = viewModelScope.launch { state.value.item?.value?.let { archive.retry(it) } }
    fun abandon() = viewModelScope.launch { state.value.item?.value?.let { archive.abandon(it) } }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("roomId") roomId: UUID, @Assisted("itemId") itemId: UUID): ArchiveDetailViewModel
    }
}
