package app.qichi.feature.calendar

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.CalendarTransferRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class CalendarTransferState(
    val busy: Boolean = false,
    val message: String? = null,
    val exportReady: Boolean = false,
    val subscriptionUrl: String? = null,
)

@HiltViewModel(assistedFactory = CalendarTransferViewModel.Factory::class)
class CalendarTransferViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val repository: CalendarTransferRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CalendarTransferState())
    val state: StateFlow<CalendarTransferState> = mutableState.asStateFlow()
    private var exportBytes: ByteArray? = null

    fun importIcs(uri: Uri) = act("导入失败，请检查文件与网络") {
        val result = repository.importIcs(roomId, uri)
        mutableState.value = CalendarTransferState(message = "导入 ${result.imported} 条，跳过 ${result.skipped} 条")
    }

    fun prepareExport() = act("导出失败，请检查网络") {
        exportBytes = repository.export(roomId)
        mutableState.value = CalendarTransferState(exportReady = true)
    }

    fun exportTo(uri: Uri?) {
        val bytes = exportBytes
        exportBytes = null
        mutableState.value = CalendarTransferState()
        if (uri == null || bytes == null) return
        act("保存失败，请重试") {
            repository.save(uri, bytes)
            mutableState.value = CalendarTransferState(message = "日历已保存")
        }
    }

    fun subscription(reset: Boolean = false) = act("获取订阅链接失败，请检查网络") {
        val link = repository.subscription(roomId, reset)
        mutableState.value = CalendarTransferState(subscriptionUrl = link.url)
    }

    fun clearExportReady() {
        mutableState.value = mutableState.value.copy(exportReady = false)
    }

    fun dismissMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun closeSubscription() { mutableState.value = mutableState.value.copy(subscriptionUrl = null) }

    private fun act(failure: String, action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = CalendarTransferState(message = failure) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    @AssistedFactory interface Factory {
        fun create(roomId: UUID): CalendarTransferViewModel
    }
}
