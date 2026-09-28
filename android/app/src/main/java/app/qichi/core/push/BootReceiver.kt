package app.qichi.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.qichi.core.reminder.ReminderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开机后恢复后台连接（开着「后台接收消息」时）；登录状态由服务自己检查，没登录就停掉。
 * 开机和更新 App 后系统清掉了所有闹钟：按本机数据把提醒重排一遍（P16-01）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SharedPrefsPushStore(context).builtIn) BackgroundConnectionService.start(context)
        val reminders = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java).reminders()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 广播里大约有 10 秒
                withTimeoutOrNull(8_000L) { reminders.rescheduleNow() }
            } finally {
                pending.finish()
            }
        }
    }
}
