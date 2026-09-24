package app.qichi.feature.me

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.data.AttachmentException
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.data.FileRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.designsystem.Feature
import app.qichi.core.designsystem.QichiShapes
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.Sizes
import app.qichi.core.designsystem.Spacing
import app.qichi.core.designsystem.component.FogSeaHero
import app.qichi.core.designsystem.component.ItemTopBar
import app.qichi.core.designsystem.component.QichiTextField
import app.qichi.core.designsystem.component.SectionLabel
import app.qichi.core.designsystem.component.TextAction
import app.qichi.core.designsystem.tsp
import app.qichi.core.network.ApiClient
import app.qichi.core.network.FileUrls
import app.qichi.core.network.NetworkMonitor
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Room
import app.qichi.shared.api.UpdateRoomRequest
import app.qichi.shared.model.FileKind
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import coil3.compose.AsyncImage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = RoomSettingsViewModel.Factory::class)
class RoomSettingsViewModel @AssistedInject constructor(
    @Assisted private val roomId: UUID,
    private val rooms: RoomRepository,
    private val preparer: AttachmentPreparer,
    private val files: FileRepository,
    private val network: NetworkMonitor,
    private val api: ApiClient,
    @ApplicationContext private val context: Context,
    val urls: FileUrls,
) : ViewModel() {
    /** 导出中：下载进度 0–1；null 表示没有在导出 */
    private val _exporting = MutableStateFlow<Float?>(null)
    val exporting: StateFlow<Float?> = _exporting.asStateFlow()

    /** 房间数据导出（需要联网）：先下载到手机的临时目录，再写进用户选的位置。 */
    fun export(target: Uri, includeFiles: Boolean) {
        if (!network.isOnline.value) {
            _messages.tryEmit("导出需要联网")
            return
        }
        if (_exporting.value != null) return
        viewModelScope.launch {
            _exporting.value = 0f
            val temp = java.io.File(context.cacheDir, "export-${UuidV7.generate()}.zip")
            try {
                api.download("rooms/$roomId/export?files=$includeFiles", temp) { received, total ->
                    _exporting.value = if (total != null && total > 0) received.toFloat() / total else 0f
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                        ?: throw java.io.IOException("写不进去")
                }
                _messages.emit("导出好了")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.emit("没能导出，再试一次")
            } finally {
                temp.delete()
                _exporting.value = null
            }
        }
    }

    val room: StateFlow<Room?> = rooms.observeRoom(roomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 主视觉照片上传中：进度 0–1；null 表示没有在传 */
    private val _heroProgress = MutableStateFlow<Float?>(null)
    val heroProgress: StateFlow<Float?> = _heroProgress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages

    /** 房间设置走发件箱：本机立即生效，离线也能改，联网后发出。 */
    fun save(change: UpdateRoomRequest) = viewModelScope.launch { rooms.updateRoom(roomId, change) }

    /** 换主视觉照片：上传（需要联网）后改房间设置，对方的今天页随之更新。 */
    fun setHero(uri: Uri) {
        if (!network.isOnline.value) {
            _messages.tryEmit("离线时不能换照片")
            return
        }
        if (_heroProgress.value != null) return
        viewModelScope.launch {
            _heroProgress.value = 0f
            try {
                val attachment = preparer.image(uri)
                val file = files.upload(roomId, UuidV7.generate(), attachment, kind = FileKind.Hero) { _heroProgress.value = it }
                preparer.cleanup(attachment)
                rooms.updateRoom(roomId, UpdateRoomRequest(heroFileId = Patch.of(file.id)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentException) {
                _messages.emit(e.message ?: "换不了这张照片")
            } catch (_: Exception) {
                _messages.emit("照片没传上去，再试一次")
            } finally {
                _heroProgress.value = null
            }
        }
    }

    /** 不用照片了：今天页回到雾海插画。 */
    fun clearHero() = save(UpdateRoomRequest(heroFileId = Patch.of(null)))

    @AssistedFactory
    interface Factory {
        fun create(roomId: UUID): RoomSettingsViewModel
    }
}

/** 今天页的主视觉：有照片显示照片，没有就是按天色画的雾海。 */
@Composable
private fun HeroSetting(heroFileId: UUID?, progress: Float?, urls: FileUrls, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    Column {
        SectionLabel("今天页的主视觉")
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(QichiShapes.card)
                .background(colors.surface),
        ) {
            if (heroFileId != null) {
                AsyncImage(
                    model = urls.thumbnail(heroFileId),
                    contentDescription = "主视觉照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FogSeaHero(Modifier.fillMaxSize())
            }
            if (progress != null) {
                Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Text("${(progress * 100).toInt()}%", style = type.numeral.copy(fontSize = 22.tsp, color = colors.ink))
                }
            }
        }
        Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextAction(if (heroFileId == null) "换成照片" else "换一张", onClick = onPick, enabled = progress == null)
            if (heroFileId != null) TextAction("用雾海插画", onClick = onClear, color = colors.muted, enabled = progress == null)
        }
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
    val heroProgress by viewModel.heroProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickHero = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setHero) }
    var includeFiles by rememberSaveable { mutableStateOf(false) }
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { viewModel.export(it, includeFiles) } }
    LaunchedEffect(Unit) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        ItemTopBar("房间设置", onBack, feature = Feature.Me)
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
            HeroSetting(
                heroFileId = current.heroFileId,
                progress = heroProgress,
                urls = viewModel.urls,
                onPick = { pickHero.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClear = viewModel::clearHero,
            )

            // ── 导出 ──
            Column {
                SectionLabel("导出房间数据")
                Text("把房间里的内容打包成一个文件：聊天记录、文稿和全部数据。撤回的内容和回收站里的不会导出。",
                    style = type.caption.copy(color = colors.muted))
                Row(Modifier.fillMaxWidth().heightIn(min = Sizes.listRow), verticalAlignment = Alignment.CenterVertically) {
                    Text("带上照片和附件（可能很大）", style = type.body.copy(color = colors.ink), modifier = Modifier.weight(1f))
                    Switch(includeFiles, { includeFiles = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent))
                }
                TextAction(
                    exporting?.let { "正在导出 ${(it * 100).toInt()}%" } ?: "导出……",
                    onClick = { saveExport.launch("qichi-export-${LocalDate.now()}.zip") },
                    enabled = exporting == null,
                )
            }
            Spacer(Modifier.height(Spacing.xl))
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
