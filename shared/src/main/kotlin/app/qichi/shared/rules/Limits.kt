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
    val QUESTION_TEXT_LENGTH: IntRange = 1..500
    val ANSWER_BODY_LENGTH: IntRange = 1..10_000
    val PLAN_TITLE_LENGTH: IntRange = 1..200
    val PLAN_STEP_LENGTH: IntRange = 1..1000
    val PLAN_LOG_LENGTH: IntRange = 1..10_000
    /** 灵感：随手记，一两句到一小段 */
    val IDEA_BODY_LENGTH: IntRange = 1..2000

    /** 共同写作：文稿标题 */
    val DOCUMENT_TITLE_LENGTH: IntRange = 1..100

    /** 共同写作：一个版本的正文最多这么多个字符（约十万字，够写很长的信） */
    const val DOCUMENT_BODY_MAX: Int = 200_000

    /** 留言板：主题标题 */
    val BOARD_TITLE_LENGTH: IntRange = 1..60

    /** 留言板：一条留言（长文留言，和聊天消息一样最多一万字） */
    val BOARD_POST_LENGTH: IntRange = 1..10_000

    /** 留言板：引用时摘录的长度 */
    const val BOARD_QUOTE_EXCERPT: Int = 80

    /** 档案：条目标题 */
    val ARCHIVE_TITLE_LENGTH: IntRange = 1..80

    /** 档案：条目正文（可以为空，只写标题） */
    const val ARCHIVE_BODY_MAX: Int = 5_000

    /** 决定：问题 */
    val DECISION_QUESTION_LENGTH: IntRange = 1..200

    /** 决定：最多几个备选、每个备选多长 */
    const val DECISION_OPTIONS_MAX: Int = 10
    val DECISION_OPTION_LENGTH: IntRange = 1..100

    /** 决定：关注点、最终决定 */
    const val DECISION_CONCERN_MAX: Int = 1_000
    val DECISION_CHOICE_LENGTH: IntRange = 1..200

    /** 阅读：书名、作者、共读计划的备注 */
    val BOOK_TITLE_LENGTH: IntRange = 1..200
    const val BOOK_AUTHOR_MAX: Int = 200
    const val BOOK_PLAN_NOTE_MAX: Int = 500

    /** 阅读：定位 JSON、选中的原文、感想 */
    const val LOCATOR_MAX: Int = 8_000
    const val HIGHLIGHT_TEXT_MAX: Int = 5_000
    const val HIGHLIGHT_NOTE_MAX: Int = 2_000

    /** 书籍离线缓存默认上限（docs/05-sync-offline.md） */
    const val BOOK_CACHE_DEFAULT_BYTES: Long = 500L * 1024 * 1024

    /** 审稿：标题、批注与讨论、原文摘录、最多多少页 */
    val REVIEW_TITLE_LENGTH: IntRange = 1..100
    val ANNOTATION_BODY_LENGTH: IntRange = 1..2_000
    val ANNOTATION_REPLY_LENGTH: IntRange = 1..2_000

    /** 文稿段落旁留言（P9-03） */
    val DOC_COMMENT_LENGTH: IntRange = 1..2_000
    /** 留言钉住的原文摘录 */
    const val DOC_COMMENT_QUOTE_MAX: Int = 200

    /** 写作助手：润色等一次最多发多少字；起标题最多带多少字正文；起草稿最长范围 */
    const val WRITE_ASSIST_TEXT_MAX: Int = 4_000
    const val WRITE_TITLES_TEXT_MAX: Int = 8_000
    const val WRITE_DRAFT_DAYS_MAX: Int = 31
    const val ANCHOR_QUOTE_MAX: Int = 500
    const val REVIEW_MAX_PAGES: Int = 300

    /** 审稿 AI：一次最多几条发现、每条最多几条证据、标题和说明多长；发给 AI 的原文最多多少字 */
    const val FINDINGS_MAX: Int = 12

    /** 一次问 AI 最多提议几个动作（P8-02） */
    const val AI_ACTIONS_MAX: Int = 5
    const val FINDING_EVIDENCE_MAX: Int = 3
    const val FINDING_TITLE_MAX: Int = 60
    const val FINDING_BODY_MAX: Int = 600
    const val REVIEW_AI_TEXT_MAX: Int = 40_000

    const val IMAGE_MAX_BYTES: Long = 20L * 1024 * 1024
    const val FILE_MAX_BYTES: Long = 100L * 1024 * 1024
    val THUMBNAIL_WIDTHS: Set<Int> = setOf(200, 400, 800)

    const val SYNC_PAGE_DEFAULT: Int = 500
    const val SYNC_PAGE_MAX: Int = 1000
    const val MESSAGE_PAGE_DEFAULT: Int = 50
    /** 搜索、回收站等游标分页接口的默认页大小 */
    const val CURSOR_PAGE_DEFAULT: Int = 30
    const val BOOTSTRAP_MESSAGES: Int = 50
}
