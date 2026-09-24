package app.qichi.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后恢复后台连接（开着「后台接收消息」时）；登录状态由服务自己检查，没登录就停掉。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SharedPrefsPushStore(context).builtIn) BackgroundConnectionService.start(context)
    }
}
