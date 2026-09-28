package app.qichi.core.push

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 安卓 13 起，App 要先得到用户同意才能弹通知；不问的话系统默认不给，所有通知都悄悄丢掉。
 * 「后台接收消息」默认开着，以前却只在「我的 → 通知」里点开关或按钮时才问，大多数人从没被问过，就一直收不到通知。
 * 现在进入主界面时问一次；拒绝了不再追着问，「我的 → 通知」里还有「打开系统通知」的入口。
 */
object NotificationPermission {
    private const val PREFS = "qichi-push"
    private const val KEY_ASKED = "askedNotificationPermission"

    /** 该不该弹系统的通知权限询问：安卓 13 及以上、还没有权限、没问过。 */
    internal fun shouldAsk(sdk: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
        sdk >= 33 && !granted && !alreadyAsked

    /** 进入主界面时调用：需要的话弹一次系统询问。 */
    @Composable
    fun AskOnce() {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (shouldAsk(Build.VERSION.SDK_INT, PushNotifier.canNotify(context), prefs.getBoolean(KEY_ASKED, false))) {
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
