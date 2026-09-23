package app.qichi.feature.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.auth.SessionManager
import app.qichi.core.data.People
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.SummaryRepository
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.sync.Local
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.ui.todayIn
import app.qichi.core.ui.zoneOf
import app.qichi.shared.api.Summary
import app.qichi.shared.model.AiJobStatus
import app.qichi.shared.model.SummaryKind
import app.qichi.shared.model.wireName
import app.qichi.shared.util.UuidV7
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
import java.time.ZoneId
import java.util.UUID

/** 正在生成的一份总结。 */
data class PendingSummary(val jobId: UUID, val label: String, val failed: Boolean = false, val retry: () -> Unit = {})

data class SummaryState(
    val people: People = People.Empty,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(),
    val summaries: List<Local<Summary>> = emptyList(),
    val pending: List<PendingSummary> = emptyList(),
    val online: Boolean = true,
    val aiEnabled: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel(assistedFactory = SummaryViewModel.Factory::class)
class SummaryViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val summaries: SummaryRepository,
    rooms: RoomRepository,
    network: NetworkMonitor,
    realtime: RealtimeClient,
    session: SessionManager,
) : ViewModel() {
    private val people = combine(rooms.observeRoom(roomId), rooms.observeMembers(roomId)) { room, members -> People(room, members, session.currentUserId) }
    private val pending = MutableStateFlow<List<PendingSummary>>(emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<SummaryState> = combine(
        people, summaries.observeSummaries(roomId), pending, network.isOnline, rooms.me,
    ) { p, list, pend, online, me ->
        val zone = zoneOf(p.room?.timezone)
        val ids = list.map { it.value.id }.toSet()
        SummaryState(p, zone, todayIn(zone), list, pend.filter { it.jobId !in ids }, online, me?.aiEnabled == true, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SummaryState())

    init {
        viewModelScope.launch {
            realtime.aiDone.collect { e ->
                if (e.status == AiJobStatus.Failed.wireName) pending.update { list -> list.map { if (it.jobId == e.jobId) it.copy(failed = true) else it } }
            }
        }
    }

    /** 生成一份；返回不能生成的原因（离线、AI 没开启），能生成时为空。 */
    fun generate(kind: SummaryKind, label: String, anchor: LocalDate? = null, start: LocalDate? = null, end: LocalDate? = null): String? {
        val s = state.value
        if (!s.online) return "需要联网"
        if (!s.aiEnabled) return "AI 还没有开启"
        val jobId = UuidV7.generate()
        fun send() = viewModelScope.launch {
            pending.update { list -> list.map { if (it.jobId == jobId) it.copy(failed = false) else it } }
            try {
                summaries.generate(roomId, jobId, kind, anchor, start, end)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pending.update { list -> list.map { if (it.jobId == jobId) it.copy(failed = true) else it } }
            }
        }
        pending.update { it + PendingSummary(jobId, label, retry = { send() }) }
        send()
        return null
    }

    fun dismiss(jobId: UUID) = pending.update { list -> list.filterNot { it.jobId == jobId } }

    fun delete(s: Summary) = viewModelScope.launch { summaries.delete(s) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): SummaryViewModel
    }
}
