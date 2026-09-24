package app.qichi.shared.rules

import app.qichi.shared.model.MessageKind

/** 消息相关的规则，服务端与客户端共用（客户端在消息发出前先按同样的规则显示回复摘要）。 */
object MessageRules {

    /**
     * 回复摘要：原消息的前 60 个字（按字符，不会切断表情；连续空白合成一个空格）。
     * 图片、文件没有正文时写「[图片]」「[文件] 文件名」；已撤回的消息没有摘要。
     */
    fun replyExcerpt(kind: MessageKind, body: String, fileName: String?, retracted: Boolean): String? {
        if (retracted) return null
        val text = body.replace(WHITESPACE, " ").trim()
        val base = when {
            text.isNotEmpty() -> text
            kind == MessageKind.Image -> "[图片]"
            kind == MessageKind.File -> listOfNotNull("[文件]", fileName?.takeIf { it.isNotBlank() }).joinToString(" ")
            else -> return null
        }
        return base.takeCodePoints(Limits.REPLY_EXCERPT_LENGTH)
    }

    /** 照片说明：空白合成一个空格、去掉首尾空白，最多 [Limits.PHOTO_CAPTION_MAX] 个字符（多出的截掉，不切断表情）。 */
    fun photoCaption(raw: String): String = raw.replace(WHITESPACE, " ").trim().takeCodePoints(Limits.PHOTO_CAPTION_MAX)

    /** 搜索词：去掉首尾空白；空的或超过 100 字返回 null。 */
    fun searchQuery(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= Limits.MESSAGE_SEARCH_QUERY_MAX }

    private val WHITESPACE = Regex("\\s+")

    private fun String.takeCodePoints(n: Int): String =
        if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
}
