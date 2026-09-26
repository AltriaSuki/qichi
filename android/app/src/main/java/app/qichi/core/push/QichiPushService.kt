package app.qichi.core.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.sync.SyncScheduler
import app.qichi.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/** ntfy（UnifiedPush 分发器）把推送地址和推送内容交到这里。 */
@AndroidEntryPoint
class QichiPushService : PushService() {
    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var session: SessionManager
    @Inject lateinit var scheduler: SyncScheduler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        appScope.launch { registrar.onNewEndpoint(endpoint.url, loggedIn()) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // 在前台时 WebSocket 已经实时同步了，不打扰
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val context = applicationContext
        // 登录状态已读出时（App 还活着）不挂起，照旧在主线程按到达顺序弹；冷启动时等读出来再弹
        appScope.launch(Dispatchers.Main.immediate) {
            if (!loggedIn()) return@launch
            scheduler.pullNow()
            PushRegistrar.parse(message.content)?.let { PushNotifier.show(context, it) }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        appScope.launch { registrar.onUnregistered(loggedIn()) }
    }

    override fun onUnregistered(instance: String) {
        appScope.launch { registrar.onUnregistered(loggedIn()) }
    }

    /**
     * App 被杀后收到推送会冷启动，这时登录状态还在读取：等读出来再判断，不能当作没登录把推送丢掉（P13-04）。
     * 连接器收到推送后绑定这个服务 5 秒，读登录状态一般不到 1 秒。
     */
    private suspend fun loggedIn(): Boolean = session.awaitLoaded() is SessionState.LoggedIn
}
