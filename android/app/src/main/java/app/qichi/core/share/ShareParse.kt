package app.qichi.core.share

import app.qichi.shared.rules.Limits

// 和 Intent.ACTION_SEND / ACTION_SEND_MULTIPLE 相同；写在这里，解析的规则不依赖安卓，能在普通 JVM 上测
const val ACTION_SEND = "android.intent.action.SEND"
const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

/** 从别的 App 分享进来的东西（P16-03）：一段文字（含链接），和 / 或几张照片。[S] 是照片的地址（App 里是 Uri）。 */
data class Shared<S>(val text: String?, val images: List<S>)

/** 一次最多收几张照片（和聊天里一次选几张一样） */
const val SHARE_MAX_IMAGES = 9

/**
 * 从分享的内容里取出文字和照片：
 * - 文字：标题（EXTRA_SUBJECT）不在正文里时放在前面；去掉首尾空白；超过一条消息的上限截掉
 * - 照片：只收 image 类型的，最多 [SHARE_MAX_IMAGES] 张
 * 什么都没有（或者不是分享）返回 null。
 */
fun <S> parseShare(action: String?, mimeType: String?, subject: String?, text: String?, streams: List<S>): Shared<S>? {
    if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null
    val body = text?.trim().orEmpty()
    val title = subject?.trim().orEmpty()
    val joined = when {
        title.isEmpty() || body.contains(title) -> body
        body.isEmpty() -> title
        else -> "$title\n$body"
    }.take(Limits.MESSAGE_BODY_MAX).ifEmpty { null }
    val images = if (mimeType?.startsWith("image/") == true) streams.take(SHARE_MAX_IMAGES) else emptyList()
    if (joined == null && images.isEmpty()) return null
    return Shared(joined, images)
}
