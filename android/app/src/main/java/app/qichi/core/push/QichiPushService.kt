package app.qichi.core.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.qichi.core.auth.SessionManager
import app.qichi.core.sync.SyncScheduler
import app.qichi.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
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
        val loggedIn = session.currentUserId != null
        appScope.launch { registrar.onNewEndpoint(endpoint.url, loggedIn) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (session.currentUserId == null) return
        // 在前台时 WebSocket 已经实时同步了，不打扰
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        scheduler.pullNow()
        PushRegistrar.parse(message.content)?.let { PushNotifier.show(applicationContext, it) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        appScope.launch { registrar.onUnregistered(session.currentUserId != null) }
    }

    override fun onUnregistered(instance: String) {
        appScope.launch { registrar.onUnregistered(session.currentUserId != null) }
    }
}
