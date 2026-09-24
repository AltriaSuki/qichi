package app.qichi.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.qichi.BuildConfig
import app.qichi.core.network.ApiClient
import app.qichi.core.network.ApiException
import app.qichi.core.network.get
import app.qichi.shared.api.AppRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** 更新到哪一步了。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * App 内置更新：问服务端最新是哪一版，比自己新就提示；在 App 里下载、核对 sha256，交给系统安装界面。
 * 第一次安装需要用户允许栖迟「安装未知应用」。签名和装着的一样，系统才会覆盖安装（数据都在）。
 */
class AppUpdater(
    private val context: Context,
    private val api: ApiClient,
    private val currentVersion: Int = BuildConfig.VERSION_CODE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("qichi-update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** 用户点了「以后」的那一版：自动检查时不再弹，手动检查时照样显示。 */
    private var dismissed: Int
        get() = prefs.getInt("dismissed", 0)
        set(value) = prefs.edit().putInt("dismissed", value).apply()

    /** 自动检查：离上次检查不到 [minIntervalMs] 就跳过；被「以后」过的版本不弹。 */
    suspend fun checkAutomatically(minIntervalMs: Long = 6 * 60 * 60 * 1000L) {
        val last = prefs.getLong("lastCheck", 0)
        if (now() - last < minIntervalMs) return
        val release = check(manual = false) ?: return
        if (release.versionCode == dismissed) _state.value = UpdateState.Idle
    }

    /** 检查一次，有新版本返回它（并把状态设成 [UpdateState.Available]）。 */
    suspend fun check(manual: Boolean = true): AppRelease? {
        if (_state.value is UpdateState.Downloading) return null
        if (manual) _state.value = UpdateState.Checking
        return try {
            val release: AppRelease = api.get("app/latest")
            prefs.edit().putLong("lastCheck", now()).apply()
            if (release.versionCode > currentVersion) {
                _state.value = UpdateState.Available(release)
                release
            } else {
                _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 404：服务端还没发布过版本
            _state.value = if (manual) (if (e.status == 404) UpdateState.UpToDate else UpdateState.Failed("没能检查更新")) else UpdateState.Idle
            null
        } catch (_: Exception) {
            _state.value = if (manual) UpdateState.Failed("检查更新要联网") else UpdateState.Idle
            null
        }
    }

    fun later(release: AppRelease) {
        dismissed = release.versionCode
        _state.value = UpdateState.Idle
    }

    fun dismissResult() {
        if (_state.value is UpdateState.UpToDate || _state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    /** 下载并核对；核对通过后状态变成 [UpdateState.ReadyToInstall]。 */
    suspend fun download(release: AppRelease) {
        val target = File(dir, "qichi-${release.versionCode}.apk")
        try {
            if (!(target.exists() && sha256(target) == release.sha256)) {
                dir.listFiles()?.forEach { it.delete() }
                _state.value = UpdateState.Downloading(release, 0f)
                api.download("app/apk", target) { received, total ->
                    val all = total ?: release.sizeBytes
                    if (all > 0) _state.value = UpdateState.Downloading(release, (received.toFloat() / all).coerceIn(0f, 1f))
                }
                if (sha256(target) != release.sha256) {
                    target.delete()
                    _state.value = UpdateState.Failed("下载的文件不完整，再试一次")
                    return
                }
            }
            _state.value = UpdateState.ReadyToInstall(release, target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            target.delete()
            _state.value = UpdateState.Failed("下载没成功，更新要联网")
        }
    }

    /** 能不能直接装（没允许「安装未知应用」时要先去设置里打开）。 */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Context) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }

    /** 交给系统安装界面。 */
    fun install(activity: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        HexFormat.of().formatHex(digest.digest())
    }
}
