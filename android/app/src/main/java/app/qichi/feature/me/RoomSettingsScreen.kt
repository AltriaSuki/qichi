package app.qichi.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.BackBar
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Room
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.rules.Limits
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@HiltViewModel(assistedFactory = RoomSettingsViewModel.Factory::class)
class RoomSettingsViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val rooms: RoomRepository,
) : ViewModel() {
    val room: StateFlow<Room?> = rooms.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 房间设置走发件箱：本机立即生效，离线也能改，联网后发出。 */
    fun save(change: UpdateRoomRequest) = viewModelScope.launch { rooms.updateRoom(roomId, change) }

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): RoomSettingsViewModel
    }
}

/** 常用时区；列表最前面是手机当前的时区。 */
private val commonZones = listOf(
    "Asia/Shanghai" to "北京、上海",
    "Asia/Hong_Kong" to "香港",
    "Asia/Taipei" to "台北",
    "Asia/Tokyo" to "东京",
    "Asia/Seoul" to "首尔",
    "Asia/Singapore" to "新加坡",
    "Europe/London" to "伦敦",
    "Europe/Paris" to "巴黎",
    "Europe/Berlin" to "柏林",
    "America/New_York" to "纽约",
    "America/Los_Angeles" to "洛杉矶",
    "America/Vancouver" to "温哥华",
    "Australia/Sydney" to "悉尼",
)

fun zoneLabel(zone: String): String = commonZones.firstOrNull { it.first == zone }?.second ?: zone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSettingsScreen(
    roomId: UUID,
    onBack: () -> Unit,
    viewModel: RoomSettingsViewModel = hiltViewModel<RoomSettingsViewModel, RoomSettingsViewModel.Factory>(key = roomId.toString()) { it.create(roomId) },
) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    var name by rememberSaveable(room?.id) { mutableStateOf(room?.name.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingZone by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        BackBar(title = "房间设置", onBack = onBack)
        val current = room ?: return@Column
        if (name.isEmpty() && current.name.isNotEmpty()) name = current.name
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.detailSection),
        ) {
            val trimmed = name.trim()
            val nameValid = trimmed.length in Limits.ROOM_NAME_LENGTH
            QichiTextField(
                value = name, onValueChange = { name = it }, label = "房间名",
                error = if (!nameValid && name.isNotEmpty()) "房间名 1–40 个字" else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (nameValid && trimmed != current.name) viewModel.save(UpdateRoomRequest(name = Patch.of(trimmed)))
                }),
            )
            if (nameValid && trimmed != current.name) {
                TextAction("保存房间名", onClick = { viewModel.save(UpdateRoomRequest(name = Patch.of(trimmed))) })
            }

            SettingRow(
                label = "纪念日",
                value = current.anniversary?.let { "${it.year} · ${it.monthValue} · ${it.dayOfMonth}" } ?: "未设置",
                numeral = current.anniversary != null,
                onClick = { pickingDate = true },
            )
            if (current.anniversary != null) {
                TextAction("清除纪念日", onClick = { viewModel.save(UpdateRoomRequest(anniversary = Patch.of(null))) }, color = colors.muted)
            }
            SettingRow(label = "时区", value = zoneLabel(current.timezone), onClick = { pickingZone = true })
        }

        if (pickingDate) {
            val initial = current.anniversary?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
            val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
            DatePickerDialog(
                onDismissRequest = { pickingDate = false },
                confirmButton = {
                    TextAction("确定", onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            viewModel.save(UpdateRoomRequest(anniversary = Patch.of<LocalDate?>(date)))
                        }
                        pickingDate = false
                    })
                },
                dismissButton = { TextAction("取消", onClick = { pickingDate = false }, color = colors.muted) },
            ) {
                DatePicker(state = dateState, title = null, headline = null, showModeToggle = false)
            }
        }

        if (pickingZone) {
            val device = ZoneId.systemDefault().id
            val zones = (listOf(device to "这台手机") + commonZones).distinctBy { it.first }
            ModalBottomSheet(onDismissRequest = { pickingZone = false }, containerColor = colors.paper) {
                LazyColumn(Modifier.padding(horizontal = Spacing.page)) {
                    items(zones, key = { it.first }) { (zone, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Sizes.listRow)
                                .clickable(role = Role.RadioButton) {
                                    viewModel.save(UpdateRoomRequest(timezone = Patch.of(zone)))
                                    pickingZone = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = type.bodyLarge.copy(color = if (zone == current.timezone) colors.accent else colors.ink),
                                modifier = Modifier.weight(1f),
                            )
                            Text(zone, style = type.caption.copy(color = colors.muted))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit, numeral: Boolean = false) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column {
        SectionLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.listRow)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = if (numeral) type.numeral.copy(fontSize = type.bodyLarge.fontSize * 1.2f, color = colors.ink) else type.bodyLarge.copy(color = colors.ink),
                modifier = Modifier.weight(1f),
            )
            TextAction("修改", onClick = onClick)
        }
    }
}
