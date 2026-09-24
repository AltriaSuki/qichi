package app.qichi.feature.writing

import app.qichi.core.ui.Markdown

/**
 * 段落旁留言找回位置（P9-03）：留言钉在一段原文（quote）上，文稿改了以后在最新正文的各块里找它。
 * 先找原样包含这段原文的块；找不到再按相似度（相邻两字重合的比例）找最像的，够像才算；都不行就是「原文已改动」。
 */
object CommentAnchors {
    private const val MIN_SIMILARITY = 0.5

    /** 一块里给人看、能被留言钉住的文字；分隔线、照片不能留言。 */
    fun text(block: Markdown.Block): String? = when (block) {
        is Markdown.Block.Heading -> block.text
        is Markdown.Block.Paragraph -> block.text
        is Markdown.Block.Item -> block.text
        is Markdown.Block.Task -> block.text
        is Markdown.Block.Quote -> block.text
        is Markdown.Block.Rule, is Markdown.Block.Image -> null
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), "")

    private fun bigrams(s: String): Set<String> = if (s.length < 2) setOf(s) else s.windowed(2).toSet()

    private fun similarity(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    /** [quote] 在 [blocks] 里的位置（块的下标），找不到返回 null。 */
    fun locate(blocks: List<Markdown.Block>, quote: String): Int? {
        val q = norm(quote)
        if (q.isEmpty()) return null
        val texts = blocks.map { b -> text(b)?.let(::norm) }
        texts.indexOfFirst { it != null && it.contains(q) }.takeIf { it >= 0 }?.let { return it }
        // 选中的一小段跨了块、或者整段被改写：找最像的一块
        return texts.withIndex()
            .filter { it.value != null }
            .map { it.index to similarity(q, it.value!!) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_SIMILARITY }
            ?.first
    }
}
