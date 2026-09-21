package app.qichi.navigation

/**
 * 深链：`qichi://room/{roomId}/{page}[/{id}]`，例如 `qichi://room/r1/chat`、`qichi://room/r1/mood/m9`。
 * 通知点击、分享链接都走这个。[page] 可以是四个标签（today / chat / together / me）或 [Page.slug]。
 */
sealed interface DeepLink {
    val roomId: String

    data class ToTab(override val roomId: String, val tab: TopTab, val id: String? = null) : DeepLink
    data class ToPage(override val roomId: String, val page: Page, val id: String? = null) : DeepLink

    companion object {
        const val SCHEME = "qichi"
        const val HOST = "room"

        /** 解析失败（格式不对、页面不认识）返回 null，App 照常打开。 */
        fun parse(uri: String?): DeepLink? {
            if (uri.isNullOrBlank()) return null
            val prefix = "$SCHEME://$HOST/"
            if (!uri.startsWith(prefix)) return null
            val segments = uri.removePrefix(prefix)
                .substringBefore('?')
                .substringBefore('#')
                .split('/')
                .filter { it.isNotEmpty() }
            if (segments.size !in 2..3) return null
            val (roomId, slug) = segments
            val id = segments.getOrNull(2)
            if (!roomId.isSafeSegment() || (id != null && !id.isSafeSegment())) return null

            TopTab.entries.firstOrNull { it.slug == slug }?.let { return ToTab(roomId, it, id) }
            Page.bySlug(slug)?.let { return ToPage(roomId, it, id) }
            return null
        }

        fun of(roomId: String, page: Page, id: String? = null): String =
            listOfNotNull("$SCHEME://$HOST", roomId, page.slug, id).joinToString("/")

        fun of(roomId: String, tab: TopTab, id: String? = null): String =
            listOfNotNull("$SCHEME://$HOST", roomId, tab.slug, id).joinToString("/")

        private fun String.isSafeSegment() = length <= 64 && all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
