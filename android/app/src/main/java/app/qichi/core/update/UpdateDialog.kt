package app.qichi.core.update

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.qichi.core.designsystem.QichiTheme
import app.qichi.core.designsystem.component.TextAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(val updater: AppUpdater) : ViewModel() {
    val state = updater.state
    fun check() = viewModelScope.launch { updater.check(manual = true) }
    fun download(release: app.qichi.shared.api.AppRelease) = viewModelScope.launch { updater.download(release) }
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)

/**
 * 有新版本时的对话框：说明 → 下载（进度）→ 安装。整个 App 里只放一个（在 QichiRoot 里）；
 * 「我的」页的「检查更新」也是改这里的状态。
 */
@Composable
fun UpdateDialog(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = QichiTheme.colors
    val type = QichiTheme.typography
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.updater.later(s.release) },
            containerColor = colors.background,
            title = { Text("有新版本 ${s.release.versionName}", style = type.pageTitle.copy(color = colors.ink)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.release.notes.isNotBlank()) Text(s.release.notes, style = type.body.copy(color = colors.ink))
                    Text("${mb(s.release.sizeBytes)} · 在栖迟里下载，装好后数据都在", style = type.caption.copy(color = colors.muted))
                }
            },
            confirmButton = { TextAction("更新", { vm.download(s.release) }) },
            dismissButton = { TextAction("以后", { vm.updater.later(s.release) }, color = colors.muted) },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = colors.background,
            title = { Text("正在下载 ${s.release.versionName}", style = type.pageTitle.copy(color = colors.ink)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.line)
                    Text("${(s.progress * 100).toInt()}%", style = type.caption.copy(color = colors.muted))
                }
            },
            confirmButton = {},
        )
        is UpdateState.ReadyToInstall -> {
            val canInstall = vm.updater.canInstall()
            AlertDialog(
                onDismissRequest = { vm.updater.later(s.release) },
                containerColor = colors.background,
                title = { Text("下载好了", style = type.pageTitle.copy(color = colors.ink)) },
                text = {
                    Text(
                        if (canInstall) "点「安装」，在系统的安装界面确认一下就好。"
                        else "第一次在栖迟里更新，要先允许栖迟「安装未知应用」：点「去允许」，打开开关后回来再点「安装」。",
                        style = type.body.copy(color = colors.ink),
                    )
                },
                confirmButton = {
                    if (canInstall) TextAction("安装", { vm.updater.install(context as? Activity ?: context, s.file) })
                    else TextAction("去允许", { vm.updater.openInstallPermission(context) })
                },
                dismissButton = { TextAction("以后", { vm.updater.later(s.release) }, color = colors.muted) },
            )
        }
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { vm.updater.dismissResult() },
            containerColor = colors.background,
            title = { Text("已经是最新版本", style = type.pageTitle.copy(color = colors.ink)) },
            confirmButton = { TextAction("好", { vm.updater.dismissResult() }) },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.updater.dismissResult() },
            containerColor = colors.background,
            title = { Text(s.message, style = type.body.copy(color = colors.ink)) },
            confirmButton = { TextAction("好", { vm.updater.dismissResult() }) },
        )
        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}
