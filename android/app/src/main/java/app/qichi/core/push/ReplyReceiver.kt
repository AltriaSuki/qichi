package app.qichi.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.qichi.core.auth.SessionManager
import app.qichi.core.auth.SessionState
import app.qichi.core.data.ChatRepository
import app.qichi.core.data.RoomRepository
import kotlinx.coroutines.flow.first
import app.qichi.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/** 在通知上直接回复：和在聊天页里发一样（先存本机，经发件箱发出，离线也不丢）。 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var session: SessionManager
    @Inject lateinit var rooms: RoomRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PushNotifier.KEY_REPLY)?.toString()?.trim().orEmpty()
        val tag = intent.getStringExtra(PushNotifier.EXTRA_TAG) ?: return
        val roomId = intent.getStringExtra(PushNotifier.EXTRA_ROOM)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        if (text.isEmpty()) return
        val pending = goAsync()
        appScope.launch {
            try {
                // App 被杀后从通知回复会冷启动，这时登录状态还在读取：等它读出来再判断，不能当作没登录把回复丢掉（P13-04）
                val state = withTimeoutOrNull(SESSION_WAIT_MS) { session.awaitLoaded() }
                if (state !is SessionState.LoggedIn) return@launch
                chat.sendText(roomId, text)
                val me = session.currentUserId
                val name = rooms.me.first()?.user?.displayName ?: "我"
                val creator = rooms.observeRoom(roomId).first()?.createdBy == me
                PushNotifier.appendMyReply(context.applicationContext, tag, text, name, creator)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** 广播接收器 goAsync 之后大约有 10 秒；读登录状态一般不到 1 秒 */
        const val SESSION_WAIT_MS = 5_000L
    }
}
