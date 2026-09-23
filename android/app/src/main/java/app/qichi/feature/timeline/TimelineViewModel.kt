package app.qichi.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TimelineRepository
import app.qichi.core.network.FileUrls
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.FileMeta
import app.qichi.shared.api.TimelinePage
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class TimelineState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val page: TimelinePage? = null,
    val loading: Boolean = true,
    /** 取不到（多半是离线） */
    val failed: Boolean = false,
    /** 选照片：本机的聊天照片 */
    val photos: List<FileMeta> = emptyList(),
    /** 每张照片被谁选中了 */
    val picks: Map<UUID, Set<UUID>> = emptyMap(),
    val picksLoaded: Boolean = false,
    val message: String? = null,
) {
    /** 当前月在「有内容的月份」里的前后（months 新的在前） */
    val older: YearMonth? get() = neighbour(+1)
    val newer: YearMonth? get() = neighbour(-1)

    private fun neighbour(step: Int): YearMonth? {
        val p = page ?: return null
        val list = p.months.map { YearMonth.of(it.year, it.month) }
        val current = YearMonth.of(p.year, p.month)
        val i = list.indexOf(current)
        if (i < 0) return (if (step > 0) list.firstOrNull { it < current } else list.lastOrNull { it > current })
        return list.getOrNull(i + step)
    }
}

private data class Remote(
    val page: TimelinePage? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val picks: Map<UUID, Set<UUID>> = emptyMap(),
    val picksLoaded: Boolean = false,
    val message: String? = null,
)

@HiltViewModel(assistedFactory = TimelineViewModel.Factory::class)
class TimelineViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val timeline: TimelineRepository,
    val urls: FileUrls,
    rooms: RoomRepository,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val remote = MutableStateFlow(Remote())
    private val myId = session.currentUserId

    val state: StateFlow<TimelineState> = combine(people, timeline.observePhotos(roomId), remote) { p, photos, r ->
        val zone = zoneOf(p.room?.timezone)
        TimelineState(p, zone, todayIn(zone), r.page, r.loading, r.failed, photos, r.picks, r.picksLoaded, r.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState())

    init {
        load(null)
    }

    fun load(month: YearMonth?) = viewModelScope.launch {
        remote.update { it.copy(loading = true, failed = false) }
        try {
            val page = timeline.month(roomId, month ?: remote.value.page?.let { YearMonth.of(it.year, it.month) })
            remote.update { it.copy(page = page, loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            remote.update { it.copy(loading = false, failed = true) }
        }
    }

    fun loadPicks() = viewModelScope.launch {
        try {
            val list = timeline.picks(roomId)
            remote.update { r -> r.copy(picks = list.groupBy({ it.fileId }, { it.userId }).mapValues { it.value.toSet() }, picksLoaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            remote.update { it.copy(message = "需要联网才能选照片") }
        }
    }

    /** 选中 / 取消：先在界面上变，失败再改回来。 */
    fun togglePick(fileId: UUID) {
        val me = myId ?: return
        val picked = me !in remote.value.picks[fileId].orEmpty()
        fun apply(on: Boolean) = remote.update { r ->
            val users = r.picks[fileId].orEmpty().let { if (on) it + me else it - me }
            r.copy(picks = r.picks + (fileId to users))
        }
        apply(picked)
        viewModelScope.launch {
            try {
                timeline.setPicked(roomId, fileId, picked)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                apply(!picked)
                remote.update { it.copy(message = "没能保存，检查一下网络") }
            }
        }
    }

    fun messageShown() = remote.update { it.copy(message = null) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): TimelineViewModel
    }
}
