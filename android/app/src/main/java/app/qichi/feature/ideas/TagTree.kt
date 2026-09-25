package app.qichi.feature.ideas

import app.qichi.shared.rules.Tags

/** 标签树里的一行：`旅行` 或 `旅行/北方`；数字算上它下面的子标签。 */
data class TagNode(
    val tag: String,
    val depth: Int,
    val ideas: Int,
    val archiveItems: Int,
    /** 同一层里的最后一个（画虚线连接用） */
    val last: Boolean = false,
) {
    val name: String get() = tag.substringAfterLast('/')
}

/**
 * 从灵感和档案的正文算出标签树：每个标签（连同它的上层）各算一次，一条内容里重复出现只算一次。
 * 顶层按用得多少排（多的在前，一样多按名字），子标签跟在父标签下面，同样排序。
 */
fun tagTree(ideaTexts: List<String>, archiveTexts: List<String>): List<TagNode> {
    fun count(texts: List<String>): Map<String, Int> =
        texts.flatMap { t -> Tags.parse(t).flatMap(Tags::withAncestors).distinct() }.groupingBy { it }.eachCount()
    val ideas = count(ideaTexts)
    val items = count(archiveTexts)
    val all = (ideas.keys + items.keys)
    val order = compareByDescending<String> { (ideas[it] ?: 0) + (items[it] ?: 0) }.thenBy { it }
    val out = mutableListOf<TagNode>()
    fun add(parent: String?, depth: Int) {
        val children = all.filter { t ->
            if (parent == null) '/' !in t else t.startsWith("$parent/") && t.count { it == '/' } == depth
        }.sortedWith(order)
        children.forEachIndexed { i, t ->
            out += TagNode(t, depth, ideas[t] ?: 0, items[t] ?: 0, last = i == children.lastIndex)
            add(t, depth + 1)
        }
    }
    add(null, 0)
    return out
}

/** 顶部筛选用的顶层标签：按用得多少排。 */
fun topTags(texts: List<String>): List<String> =
    texts.flatMap { t -> Tags.parse(t).map { it.substringBefore('/') }.distinct() }
        .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
