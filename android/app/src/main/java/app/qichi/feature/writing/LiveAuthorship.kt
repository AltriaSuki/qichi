package app.qichi.feature.writing

import app.qichi.shared.util.Authorship
import java.util.UUID
import kotlin.math.min

/**
 * 编辑框里的署名（P10-08）：对方写的字在哪些位置、各人写了多少字，算的是 [text] 这份正文。
 *
 * 逐字比较在后台做、停手片刻再算（P13-19）：长文稿每按一个键都在界面线程上从头比较一遍会卡。
 * 结果还没跟上正在写的正文时，用 [rangesFor] 按这次改动把对方的位置挪一挪，底色不会错位。
 */
internal class LiveAuthorship(val text: String, val partnerRanges: List<IntRange>, val counts: Map<UUID, Int>) {

    /**
     * 对方写的字在 [current] 里的位置。和 [text] 比，只看头尾相同的部分（一次按键、一次粘贴都是一处连续的改动）：
     * 改动之前的不变，之后的整体平移，落在改动里的去掉（新写的算我的，和后台算出来的一致）。
     */
    fun rangesFor(current: String): List<IntRange> {
        if (current === text || current == text) return partnerRanges
        val prefix = commonPrefix(text, current)
        val suffix = commonSuffix(text, current, min(text.length, current.length) - prefix)
        val changedEnd = text.length - suffix
        val shift = current.length - text.length
        val moved = partnerRanges.flatMap { r ->
            when {
                r.last < prefix -> listOf(r)
                r.first >= changedEnd -> listOf(r.first + shift..r.last + shift)
                else -> listOfNotNull(
                    (r.first until prefix).takeUnless { it.isEmpty() },
                    (changedEnd + shift..r.last + shift).takeUnless { it.isEmpty() },
                )
            }
        }
        // 删掉中间一段后，两边挨上了的并成一段
        val out = ArrayList<IntRange>(moved.size)
        for (r in moved) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    companion object {
        val Empty = LiveAuthorship("", emptyList(), emptyMap())

        /** 后台调用：在存过的版本算出的底子 [base] 上接上编辑框里的 [text]（还没存的改动算 [me] 的）。 */
        fun compute(base: List<Authorship.Run>, text: String, me: UUID): LiveAuthorship {
            val runs = Authorship.extend(base, text, me)
            var at = 0
            val partner = runs.mapNotNull { r ->
                val range = at until at + r.text.length
                at += r.text.length
                range.takeIf { r.authorId != me }
            }
            return LiveAuthorship(text, partner, Authorship.counts(runs))
        }

        private fun commonPrefix(a: String, b: String): Int {
            val n = min(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            return i
        }

        /** 末尾相同的长度，最多 [max]（不和开头相同的部分重叠）。 */
        private fun commonSuffix(a: String, b: String, max: Int): Int {
            var i = 0
            while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
            return i
        }
    }
}
