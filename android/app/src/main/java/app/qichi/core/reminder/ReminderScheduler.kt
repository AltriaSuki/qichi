package app.qichi.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.data.EventRepository
import app.qichi.core.data.RoomRepository
import app.qichi.core.data.TodoRepository
import app.qichi.core.ui.zoneOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把本机的日程、待办、纪念日排成系统闹钟（P16-01、P16-02）：数据一变（自己改的、同步来的）就重排，离线也会响。
 * 排过哪些记在本机，重排时先取消不再需要的；退出登录、换房间时全部取消。
 * 开机后系统会清掉所有闹钟，[BootReceiver][app.qichi.core.push.BootReceiver] 调 [rescheduleNow] 重排。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val rooms: RoomRepository,
    private val events: EventRepository,
    private val todos: TodoRepository,
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(): Flow<List<Reminder>> =
        combine(session.state.filter { it !is SessionState.Loading }, rooms.currentRoomId, ticker()) { state, roomId, _ ->
            (state as? SessionState.LoggedIn)?.userId to roomId
        }.flatMapLatest { (me, roomId) ->
            if (me == null || roomId == null) {
                flowOf(emptyList())
            } else {
                combine(rooms.observeRoom(roomId), events.observeEvents(roomId), todos.observeTodos(roomId)) { room, e, t ->
                    upcomingReminders(roomId, e.map { it.value }, t.map { it.value }, me, zoneOf(room?.timezone), Instant.now(), anniversary = room?.anniversary)
                }
            }
        }

    /** 只排 [REMIND_HORIZON] 以内的：隔几个小时重算一次，把新进入范围的排上。 */
    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            observe()
                .distinctUntilChanged()
                // 同步一次会连着改好几条：停下来再重排
                .debounce(DEBOUNCE_MS)
                .collect { apply(it) }
        }
    }

    /** 立刻按现在的数据重排一次（开机后）。 */
    suspend fun rescheduleNow() {
        session.awaitLoaded()
        apply(observe().first())
    }

    @Synchronized
    private fun apply(reminders: List<Reminder>) {
        val keys = reminders.map { it.key }.toSet()
        val old = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (old - keys).forEach { cancel(it) }
        reminders.forEach { schedule(it) }
        prefs.edit().putStringSet(KEY_SCHEDULED, keys).apply()
    }

    private fun intent(key: String) = Intent(context, ReminderReceiver::class.java)
        .setAction(ReminderReceiver.ACTION)
        // 每件事一个不同的 data，闹钟之间不互相覆盖，取消时也能找回同一个
        .setData(Uri.parse("qichi-reminder://$key"))

    private fun schedule(r: Reminder) {
        val intent = intent(r.key)
            .putExtra(ReminderReceiver.EXTRA_KEY, r.key)
            .putExtra(ReminderReceiver.EXTRA_TITLE, r.title)
            .putExtra(ReminderReceiver.EXTRA_TEXT, r.text)
            .putExtra(ReminderReceiver.EXTRA_LINK, r.link)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = r.at.toEpochMilli()
        try {
            // 安卓 12 起准点闹钟要权限（安卓 13 起日历类 App 声明了就有）；没有时退一步用不那么准的，最多晚几分钟
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancel(key: String) {
        PendingIntent.getBroadcast(context, 0, intent(key), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { alarms.cancel(it); it.cancel() }
    }

    private companion object {
        const val PREFS = "qichi-reminders"
        const val KEY_SCHEDULED = "scheduled"
        const val DEBOUNCE_MS = 500L
        const val TICK_MS = 6 * 60 * 60 * 1000L
    }
}

/** 开机广播不经过 Hilt，从这里拿。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun reminders(): ReminderScheduler
}
