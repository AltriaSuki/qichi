package app.qichi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.core.network.NetworkMonitor
import app.qichi.core.push.BackgroundConnectionService
import app.qichi.core.push.PushNotifier
import app.qichi.core.push.PushRegistrar
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
class QichiApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionManager
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var realtime: RealtimeClient
    @Inject lateinit var db: QichiDatabase
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var api: ApiClient
    @Inject lateinit var push: PushRegistrar
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private var inForeground = false

    /** 图片经 ApiClient 的 HTTP 客户端加载（自动带令牌）；文件内容不变，磁盘缓存可以长留。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { api.http })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                if (session.currentUserId != null) onForegroundLoggedIn()
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                // 开着「后台接收消息」时连接留着（由后台服务撑着），否则断开
                if (!(push.builtIn.value && session.currentUserId != null)) realtime.stop()
            }
        })

        appScope.launch {
            session.state.collectLatest { state ->
                when (state) {
                    is SessionState.LoggedIn -> {
                        scheduler.schedulePeriodicSync()
                        // 推送已开启的话，把推送地址登记到（可能是新登录的）这个账号
                        launch { push.upload() }
                        if (inForeground) onForegroundLoggedIn()
                    }
                    SessionState.LoggedOut -> {
                        realtime.stop()
                        BackgroundConnectionService.stop(this@QichiApplication)
                    }
                    SessionState.Loading -> Unit
                }
            }
        }

        appScope.launch {
            // 内置通知：App 不在前台时弹出来（在前台时聊天页、今天页已经实时显示了）
            realtime.notifications.collect { e ->
                if (!inForeground && session.currentUserId != null) PushRegistrar.sanitize(e.payload)?.let { PushNotifier.show(this@QichiApplication, it) }
            }
        }

        appScope.launch {
            // 联网恢复：马上把发件箱里攒下的发出去
            network.isOnline.collect { online ->
                if (online && session.currentUserId != null) scheduler.kickOutbox(now = true)
            }
        }
    }

    private fun onForegroundLoggedIn() {
        realtime.start()
        // 后台服务只能在 App 在前台时启动（安卓的限制）
        if (push.builtIn.value) BackgroundConnectionService.start(this) else BackgroundConnectionService.stop(this)
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
