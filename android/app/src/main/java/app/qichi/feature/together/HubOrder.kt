package app.qichi.feature.together

// 「一起」里功能的先后（P16-09）：纯逻辑，按页面的 slug 算，排法存在本机（HubOrderStore）。

/** 按存下的顺序排；存下的里面没有的（新加的功能）按默认顺序接在后面，已经没有的功能丢掉。 */
fun arrange(defaults: List<String>, saved: List<String>?): List<String> {
    if (saved.isNullOrEmpty()) return defaults
    val known = saved.filter { it in defaults }.distinct()
    return known + defaults.filter { it !in known }
}

/** 按我点开的次数从多到少排；次数一样的保持原来的先后。 */
fun byUsage(current: List<String>, uses: Map<String, Int>): List<String> =
    current.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { uses[it.value] ?: 0 }.thenBy { it.index }).map { it.value }

/** 把 [item] 往前（-1）或往后（+1）挪一位；到头了不动。 */
fun move(list: List<String>, item: String, delta: Int): List<String> {
    val from = list.indexOf(item)
    val to = from + delta
    if (from < 0 || to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}
