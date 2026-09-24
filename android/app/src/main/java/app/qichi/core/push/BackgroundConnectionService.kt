package app.qichi.core.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.qichi.MainActivity
import app.qichi.R
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.sync.RealtimeClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 内置通知（不用装 ntfy）：App 不在前台时也保持和服务器的实时连接，有人发消息就直接弹通知（见 QichiApplication）。
 * 安卓要求这种常驻连接在通知栏挂一条通知，这里用最低调的渠道「后台连接」，用户可以在系统设置里把它折叠起来。
 */
@AndroidEntryPoint
class BackgroundConnectionService : Service() {
    @Inject lateinit var realtime: RealtimeClient
    @Inject lateinit var session: SessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = onStart(
        foreground = {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(this),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        },
        whenLoaded = { decide ->
            scope.launch { decide(session.state.first { it !is SessionState.Loading } is SessionState.LoggedIn) }
        },
        connect = { realtime.start() },
        stop = { stopSelf() },
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * 启动时的顺序：**先**挂上通知（startForegroundService 启动的服务必须在几秒内这样做，哪怕马上要停，
         * 否则安卓让 App 崩溃），再等登录状态读出来（开机、装新版本后系统重启服务时它还在读），登录了就连上，没登录就停。
         */
        internal fun onStart(
            foreground: () -> Unit,
            whenLoaded: (decide: (loggedIn: Boolean) -> Unit) -> Unit,
            connect: () -> Unit,
            stop: () -> Unit,
        ): Int {
            foreground()
            whenLoaded { loggedIn -> if (loggedIn) connect() else stop() }
            // 被系统杀掉后，有机会就重启（停掉的不会重启）
            return START_STICKY
        }

        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 7

        /** 开着「后台接收消息」且已登录时启动；要在 App 在前台时（或开机时）调用，安卓不允许从后台随意启动。 */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, BackgroundConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundConnectionService::class.java))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "让栖迟在后台也能收到消息。可以把这个类别设为「静默」或折叠"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("栖迟正在接收消息")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
