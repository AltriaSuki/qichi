package app.qichi.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.qichi.core.push.PushNotifier
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * 闹钟到点：弹一条提醒通知（P16-01）。内容在排闹钟时就放进来了；
 * 弹之前先确认这条提醒还算数（对方可能已经做完、删掉、改了时间，P21-08），确认不了（超时）照样弹，宁可多提醒一次。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val link = intent.getStringExtra(EXTRA_LINK) ?: return
        // 旧版本排的闹钟没记时间：不确认，直接弹
        val at = intent.getLongExtra(EXTRA_AT, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val due = at == null || withTimeoutOrNull(CHECK_MS) {
                    try {
                        EntryPointAccessors.fromApplication(app, ReminderEntryPoint::class.java).reminders().stillDue(key, at)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        true
                    }
                } ?: true
                if (due) PushNotifier.showReminder(app, key = key, title = title, text = text, link = link)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.qichi.REMIND"
        const val EXTRA_KEY = "key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LINK = "link"
        const val EXTRA_AT = "at"

        /** 广播里大约有 10 秒：确认最多用这么久，剩下的留给弹通知 */
        private const val CHECK_MS = 8_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
