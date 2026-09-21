package app.qichi.shared.rules

/**
 * 两端共用的长度与范围限制，与 api/openapi.yaml、V1__init.sql 的约束保持一致。
 * 客户端用它在输入时提示，服务端用它校验请求。
 */
object Limits {
    val USERNAME_PATTERN: Regex = Regex("^[a-z0-9_]{3,32}$")
    val PASSWORD_LENGTH: IntRange = 8..128
    val DISPLAY_NAME_LENGTH: IntRange = 1..32
    const val DEVICE_NAME_MAX: Int = 64

    val ROOM_NAME_LENGTH: IntRange = 1..40
    const val DEFAULT_TIMEZONE: String = "Asia/Shanghai"
    const val MAX_ROOM_MEMBERS: Int = 2

    /** 邀请码：8 位，去掉 0/O、1/I/L 等易混字符。 */
    const val INVITE_ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val INVITE_LENGTH: Int = 8
    const val INVITE_VALID_DAYS: Long = 7

    val MOOD_INTENSITY: IntRange = 1..10
    const val MOOD_NOTE_MAX: Int = 500

    val TODO_TITLE_LENGTH: IntRange = 1..200
    const val NOTE_MAX: Int = 2000
    val EVENT_TITLE_LENGTH: IntRange = 1..200
    const val EVENT_LOCATION_MAX: Int = 200

    const val MESSAGE_BODY_MAX: Int = 10_000
    const val REPLY_EXCERPT_LENGTH: Int = 60
    const val MESSAGE_SEARCH_QUERY_MAX: Int = 100

    const val IMAGE_MAX_BYTES: Long = 20L * 1024 * 1024
    const val FILE_MAX_BYTES: Long = 100L * 1024 * 1024
    val THUMBNAIL_WIDTHS: Set<Int> = setOf(200, 400, 800)

    const val SYNC_PAGE_DEFAULT: Int = 500
    const val SYNC_PAGE_MAX: Int = 1000
    const val MESSAGE_PAGE_DEFAULT: Int = 50
    const val BOOTSTRAP_MESSAGES: Int = 50
}
