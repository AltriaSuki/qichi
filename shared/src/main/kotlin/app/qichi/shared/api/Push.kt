package app.qichi.shared.api

import kotlinx.serialization.Serializable

/**
 * 服务端经 UnifiedPush 发给手机的内容（明文 JSON，只经过自建的 ntfy）。像 QQ 那样：标题是谁，正文是说了什么；
 * 对方在「通知」里关掉「通知里显示内容」时，正文只有「谁做了什么」。
 * 不在 openapi.yaml 里：它不走 HTTP 接口，而是经推送服务器（ntfy）转给 App。
 */
@Serializable
data class PushPayload(
    val title: String,
    val body: String,
    /** qichi://room/{roomId}/{page}[/{id}] */
    val link: String,
    /** 同一个 tag 的通知在手机上合并成一条（比如同一个房间的聊天） */
    val tag: String,
    /** message = 聊天消息（手机上按聊天记录的样子叠起来、能直接回复）；event = 其它动态 */
    val kind: String = KIND_EVENT,
    /** 谁做的（名字、用户 id、是不是房间创建者——决定圆标的颜色） */
    val sender: String? = null,
    val senderId: Id? = null,
    val senderIsCreator: Boolean = false,
    val roomId: Id? = null,
    val roomName: String? = null,
    /** 聊天消息的 id（手机上去重、回复时引用） */
    val messageId: Id? = null,
    val sentAt: Timestamp? = null,
) {
    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_EVENT = "event"

        /** 推送里消息内容最多多少字 */
        const val PREVIEW_MAX = 300
    }
}
