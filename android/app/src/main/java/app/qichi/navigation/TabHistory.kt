package app.qichi.navigation

/**
 * 标签的切换历史：在某个标签的根页面按返回键时，回到上一个用过的标签（而不是直接退出）。
 * 历史里同一个标签只出现一次，最近用过的在最后。
 */
class TabHistory(initial: List<TopTab> = emptyList()) {
    private val stack = ArrayDeque(initial)

    val entries: List<TopTab> get() = stack.toList()
    val isEmpty: Boolean get() = stack.isEmpty()

    /** 从 [from] 切到 [to] 时调用。 */
    fun onSwitch(from: TopTab, to: TopTab) {
        if (from == to) return
        stack.remove(from)
        stack.addLast(from)
        stack.remove(to)
    }

    /** 取出上一个标签（按返回键时）；没有历史返回 null。 */
    fun popPrevious(current: TopTab): TopTab? {
        stack.remove(current)
        return stack.removeLastOrNull()
    }
}
