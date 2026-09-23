package app.qichi.shared.api

import kotlinx.serialization.Serializable

/**
 * 服务端经 UnifiedPush 发给手机的内容（明文 JSON）：只有「谁做了什么」和点开后去哪里（深链），不含正文。
 * 不在 openapi.yaml 里：它不走 HTTP 接口，而是经推送服务器（ntfy）转给 App。
 */
@Serializable
data class PushPayload(
    val title: String,
    val body: String,
    /** qichi://room/{roomId}/{page}[/{id}] */
    val link: String,
    /** 同一个 tag 的通知在手机上合并成一条（比如同一个房间的新消息） */
    val tag: String,
)
