package app.qichi.shared.util

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import java.util.UUID

/**
 * 署名（P10-08）：文稿里每个字是谁写的，由版本历史逐字比较得出（和 [Diff] 一样用 Myers 算法，只是按字而不是按行）。
 *
 * 从第一版开始，每一版和上一版逐字比较：没动的字保留原来的作者，新加的字算这一版作者的，删掉的字就没了。
 * 所以改了别人的一个字，那个字就算改的人的；只改标点也一样。
 */
object Authorship {
    /** 一段连续的、同一个人写的字。 */
    data class Run(val text: String, val authorId: UUID)

    /**
     * @param versions 按版本号从小到大：(那一版的全文, 存这一版的人)。编辑器里还没存的内容可以作为最后一版传进来。
     */
    fun attribute(versions: List<Pair<String, UUID>>): List<Run> {
        if (versions.isEmpty()) return emptyList()
        val (first, firstAuthor) = versions.first()
        var runs = if (first.isEmpty()) emptyList() else listOf(Run(first, firstAuthor))
        for ((text, author) in versions.drop(1)) runs = extend(runs, text, author)
        return runs
    }

    /**
     * 在已经算好的署名上再接一版：[runs] 拼起来是上一版全文，[text] 是新的全文，改动算 [author] 的。
     * 编辑器里边写边算用它（只比较最后一步，不用从第一版重来）。
     */
    fun extend(runs: List<Run>, text: String, author: UUID): List<Run> {
        val chars = ArrayList<Int>()
        val authors = ArrayList<UUID>()
        runs.forEach { r -> r.text.codePoints().forEach { chars += it; authors += r.authorId } }
        val next = text.codePoints().toArray().toList()
        val nextAuthors = ArrayList<UUID>(next.size)
        var o = 0
        val deltas = DiffUtils.diff(chars, next).deltas.sortedBy { it.source.position }
        for (d in deltas) {
            // 这一处改动之前没动的字：原样保留作者
            while (o < d.source.position) { nextAuthors += authors[o]; o++ }
            if (d.type == DeltaType.DELETE || d.type == DeltaType.CHANGE) o += d.source.size()
            if (d.type == DeltaType.INSERT || d.type == DeltaType.CHANGE) repeat(d.target.size()) { nextAuthors += author }
        }
        while (o < chars.size) { nextAuthors += authors[o]; o++ }
        // 连成一段段
        val out = mutableListOf<Run>()
        val sb = StringBuilder()
        var current: UUID? = null
        next.forEachIndexed { i, cp ->
            if (nextAuthors[i] != current && sb.isNotEmpty()) {
                out += Run(sb.toString(), current!!)
                sb.setLength(0)
            }
            current = nextAuthors[i]
            sb.appendCodePoint(cp)
        }
        if (sb.isNotEmpty()) out += Run(sb.toString(), current!!)
        return out
    }

    /** 每个人写了多少字（按 [CjkText.charCount] 的规则：汉字一个一个数，英文按词，标点不算）。 */
    fun counts(runs: List<Run>): Map<UUID, Int> =
        runs.groupBy { it.authorId }.mapValues { (_, rs) -> CjkText.charCount(rs.joinToString("") { it.text }) }
}
