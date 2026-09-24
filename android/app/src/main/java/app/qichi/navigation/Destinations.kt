package app.qichi.navigation

import kotlinx.serialization.Serializable

/** 底部四个标签。 */
@androidx.annotation.Keep
enum class TopTab(val label: String, val slug: String) {
    Today("今天", "today"),
    Chat("聊天", "chat"),
    Together("一起", "together"),
    Me("我的", "me"),
}

/** 「一起」入口页的三组。 */
enum class TogetherGroup(val label: String) { Life("生活"), Create("创作"), Look("回看") }

/**
 * 标签之下的二级页面。[slug] 用在深链 `qichi://room/{roomId}/{slug}[/{id}]` 里。
 * 「一起」下的页面压在「一起」的返回栈里；「我的」下的页面压在「我的」的返回栈里。
 */
@androidx.annotation.Keep
enum class Page(val slug: String, val title: String, val tab: TopTab, val group: TogetherGroup? = null) {
    // 一起 · 生活
    Mood("mood", "心情", TopTab.Together, TogetherGroup.Life),
    Qna("qna", "问答", TopTab.Together, TogetherGroup.Life),
    Plan("plan", "计划", TopTab.Together, TogetherGroup.Life),
    Todo("todo", "待办", TopTab.Together, TogetherGroup.Life),
    Calendar("calendar", "日历", TopTab.Together, TogetherGroup.Life),
    Ideas("ideas", "灵感", TopTab.Together, TogetherGroup.Life),

    // 一起 · 创作
    Board("board", "留言", TopTab.Together, TogetherGroup.Create),
    Writing("writing", "共同写作", TopTab.Together, TogetherGroup.Create),

    // 一起 · 回看
    Archive("archive", "档案", TopTab.Together, TogetherGroup.Look),
    Decisions("decisions", "决定", TopTab.Together, TogetherGroup.Look),
    Timeline("timeline", "时间线", TopTab.Together, TogetherGroup.Look),
    Reading("reading", "阅读", TopTab.Together, TogetherGroup.Look),
    Review("review", "审稿", TopTab.Together, TogetherGroup.Look),
    Summary("summary", "总结", TopTab.Together, TogetherGroup.Look),

    // 我的
    MyContent("my-content", "我写下的内容", TopTab.Me),
    AiUsage("ai-usage", "我发起的 AI 使用", TopTab.Me),
    Members("members", "成员与邀请", TopTab.Me),
    RoomSettings("room-settings", "房间设置", TopTab.Me),
    Trash("trash", "回收站", TopTab.Me),
    Profile("profile", "资料", TopTab.Me),
    Display("display", "显示", TopTab.Me),
    Notifications("notifications", "通知", TopTab.Me),
    AiPrefs("ai-prefs", "AI 能看什么", TopTab.Me),
    Security("security", "安全", TopTab.Me),
    /** 组件陈列（开发用，只在调试版的「我的」里出现） */
    Showcase("showcase", "组件陈列", TopTab.Me),
    ;

    companion object {
        fun bySlug(slug: String): Page? = entries.firstOrNull { it.slug == slug }
        fun inGroup(group: TogetherGroup): List<Page> = entries.filter { it.group == group }
    }
}

// ── 类型安全路由 ──
// 每个标签是一个嵌套导航图，各自保存返回栈。

@Serializable data object TodayGraph
@Serializable data object ChatGraph
@Serializable data object TogetherGraph
@Serializable data object MeGraph

@Serializable data object TodayHome

/** @param jumpTo 进入聊天后跳到这条消息（通知、回复摘要用） */
@Serializable data class ChatHome(val jumpTo: String? = null)

@Serializable data object TogetherHome
@Serializable data object MeHome

/** 「一起」下的二级页面。 */
@Serializable data class TogetherPage(val page: Page, val id: String? = null)

/** 「我的」下的二级页面。 */
@Serializable data class MePage(val page: Page, val id: String? = null)

/** 日历某一天的详情。 */
@Serializable data class CalendarDay(val date: String)

/** 现有日程列表与编辑入口。 */
@Serializable data object EventList

fun TopTab.graph(): Any = when (this) {
    TopTab.Today -> TodayGraph
    TopTab.Chat -> ChatGraph
    TopTab.Together -> TogetherGraph
    TopTab.Me -> MeGraph
}

fun Page.route(id: String? = null): Any = when (tab) {
    TopTab.Me -> MePage(this, id)
    else -> TogetherPage(this, id)
}
