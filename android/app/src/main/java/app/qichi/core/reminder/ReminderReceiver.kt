package app.qichi.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.qichi.core.push.PushNotifier

/** 闹钟到点：弹一条提醒通知（P16-01）。内容在排闹钟时就放进来了，不用再读数据库。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        PushNotifier.showReminder(
            context,
            key = key,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            link = intent.getStringExtra(EXTRA_LINK) ?: return,
        )
    }

    companion object {
        const val ACTION = "app.qichi.REMIND"
        const val EXTRA_KEY = "key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LINK = "link"
    }
}
