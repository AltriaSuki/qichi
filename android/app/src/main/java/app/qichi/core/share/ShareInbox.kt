package app.qichi.core.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

fun Intent.sharedContent(): Shared<Uri>? {
    val streams: List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
    return parseShare(action, type, getStringExtra(Intent.EXTRA_SUBJECT), getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), streams)
}

/**
 * 分享进来、还没处理的内容。先放在 [pending]，界面问「发到聊天」还是「存成灵感」；
 * 选了聊天就挪到 [forChat]，由那个房间的聊天页取走：文字放进输入框（看一眼再发），照片开始上传。
 */
@Singleton
class ShareInbox @Inject constructor() {
    private val _pending = MutableStateFlow<Shared<Uri>?>(null)
    val pending: StateFlow<Shared<Uri>?> = _pending.asStateFlow()

    private val _forChat = MutableStateFlow<Pair<UUID, Shared<Uri>>?>(null)
    val forChat: StateFlow<Pair<UUID, Shared<Uri>>?> = _forChat.asStateFlow()

    fun offer(content: Shared<Uri>) {
        _pending.value = content
    }

    fun dismiss() {
        _pending.value = null
    }

    fun sendToChat(roomId: UUID) {
        val content = _pending.getAndUpdate { null } ?: return
        _forChat.value = roomId to content
    }

    /** 聊天页取走给它的内容（只取一次）。 */
    fun takeForChat(roomId: UUID): Shared<Uri>? {
        val current = _forChat.value ?: return null
        if (current.first != roomId) return null
        return if (_forChat.compareAndSet(current, null)) current.second else null
    }
}
