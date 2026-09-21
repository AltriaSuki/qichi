package app.qichi

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.database.QichiDatabase
import app.qichi.core.sync.RealtimeClient
import app.qichi.core.sync.SyncEngine
import app.qichi.core.sync.SyncScheduler
import app.qichi.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 同步的触发时机（docs/05-sync-offline.md §3.2）：App 启动与回到前台、WebSocket 收到 changed、
 * 发件箱发完一批、联网恢复、每 15 分钟。
 */
@HiltAndroidApp
class QichiApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionManager
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var realtime: RealtimeClient
    @Inject lateinit var db: QichiDatabase
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private var inForeground = false

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                if (session.currentUserId != null) onForegroundLoggedIn()
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                realtime.stop()
            }
        })

        appScope.launch {
            session.state.collectLatest { state ->
                when (state) {
                    is SessionState.LoggedIn -> {
                        scheduler.schedulePeriodicSync()
                        if (inForeground) onForegroundLoggedIn()
                    }
                    SessionState.LoggedOut -> realtime.stop()
                    SessionState.Loading -> Unit
                }
            }
        }

        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (session.currentUserId != null) scheduler.kickOutbox(now = true)
                }
            },
        )
    }

    private fun onForegroundLoggedIn() {
        realtime.start()
        scheduler.kickOutbox(now = true)
        appScope.launch { pullAll() }
    }

    private suspend fun pullAll() {
        for (room in db.syncState().roomIds()) {
            try {
                syncEngine.pull(UUID.fromString(room))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
