package app.qichi.navigation

/**
 * 打开「一起」下的某页时返回栈怎么变：先退到 [popTo]（为空 = 「一起」首页），再依次压入 [push]。
 * 目的是让返回键永远守规矩：功能首页回「一起」，单项页回它的功能首页（docs/06-design-system.md §5）。
 */
data class OpenPlan(val popTo: TogetherPage?, val push: List<TogetherPage>)

/**
 * @param top 当前最上面的页面（不是「一起」下的页面时为空）
 * @param homeOnStack 这个功能的首页是不是已经在返回栈里
 * @param id 单项的 id；以 `new:` 开头的是功能首页的一种打开方式（如从聊天存进档案），不算单项
 */
fun planOpen(top: TogetherPage?, homeOnStack: Boolean, page: Page, id: String?): OpenPlan {
    val home = TogetherPage(page, null)
    val target = TogetherPage(page, id)
    val isItem = id != null && !id.startsWith("new:")
    return when {
        // 已经在这一页：不动
        top == target -> OpenPlan(target, emptyList())
        // 回到已经打开过的功能首页，保留它的滚动位置
        id == null && homeOnStack -> OpenPlan(home, emptyList())
        !isItem -> OpenPlan(null, listOf(target))
        homeOnStack -> OpenPlan(home, listOf(target))
        else -> OpenPlan(null, listOf(home, target))
    }
}
