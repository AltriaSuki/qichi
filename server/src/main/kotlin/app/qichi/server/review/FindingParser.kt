package app.qichi.server.review

import app.qichi.shared.api.FindingEvidence
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.rules.Limits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** AI 给出的一条发现（已经核对过证据）。 */
data class ParsedFinding(val title: String, val body: String, val evidence: List<FindingEvidence>)

/**
 * 解析 AI 的回答（一个 JSON 数组），并核对每条证据：摘录必须是原文里一字不差的话（空白不计）。
 * 编号对不上时在全文里找这句话；哪里都找不到的证据丢掉，一条证据都没有的发现也丢掉——保证「带原文证据」。
 */
object FindingParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(answer: String, pages: List<ReviewPage>): List<ParsedFinding> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
        val blocks = pages.flatMap { p -> p.blocks.map { b -> Triple(p.page, b, compact(b.text)) } }
        val byId = blocks.associateBy { it.second.id }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.string("title").trim().take(Limits.FINDING_TITLE_MAX)
            if (title.isEmpty()) return@mapNotNull null
            val evidence = (obj["evidence"] as? JsonArray).orEmpty().mapNotNull { e ->
                val ev = e as? JsonObject ?: return@mapNotNull null
                val quote = ev.string("quote").trim()
                val q = compact(quote)
                if (q.length < 2) return@mapNotNull null
                val hit = byId[ev.string("ref").trim()]?.takeIf { it.third.contains(q) } ?: blocks.firstOrNull { it.third.contains(q) }
                hit?.let { (page, block, _) -> FindingEvidence(page, block.id, quote.take(Limits.ANCHOR_QUOTE_MAX), block.rect) }
            }.distinctBy { it.ref to compact(it.quote) }.take(Limits.FINDING_EVIDENCE_MAX)
            if (evidence.isEmpty()) null else ParsedFinding(title, obj.string("body").trim().take(Limits.FINDING_BODY_MAX), evidence)
        }.take(Limits.FINDINGS_MAX)
    }

    /** 去掉所有空白再比较（PDF 里的换行、空格常和原文不一样）。 */
    fun compact(text: String) = text.replace(Regex("\\s+"), "")

    private fun JsonObject.string(name: String) = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
}
