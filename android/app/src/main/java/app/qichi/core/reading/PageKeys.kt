package app.qichi.core.reading

import android.view.KeyEvent

/**
 * 音量键翻页（P20-01）：阅读页打开、并且打开了「音量键翻页」时装上 [handler]，MainActivity 先把按键交给它。
 * 返回 true 表示按键用掉了（不再调音量）。
 */
object PageKeys {
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}
