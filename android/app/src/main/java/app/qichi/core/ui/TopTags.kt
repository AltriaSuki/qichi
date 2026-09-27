package app.qichi.core.ui

import app.qichi.shared.rules.Tags

/** 灵感、档案顶部筛选用的顶层标签（两页共用，P15-04 从灵感页挪过来）：按用得多少排，一样多按名字。 */
fun topTags(texts: List<String>): List<String> =
    texts.flatMap { t -> Tags.parse(t).map { it.substringBefore('/') }.distinct() }
        .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
