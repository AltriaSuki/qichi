package app.qichi.server.ai.tools

import app.qichi.server.summaries.SourceLine
import app.qichi.shared.api.SummarySource
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.wireName
import java.time.Instant
import java.util.UUID

/**
 * 一次问 AI 里给 AI 看过的所有来源（P11）：事先备料的和工具查到的共用一套编号 [n]。
 * 同一条记录再次出现时沿用原来的编号，AI 在回答里引用哪个都能对上。
 */
class SourceBook(initial: List<SourceLine> = emptyList()) {
    private val byNumber = linkedMapOf<Int, SummarySource>()
    private val byKey = hashMapOf<Pair<String, UUID>, Int>()

    init {
        initial.forEach { put(it.source) }
    }

    private fun put(source: SummarySource) {
        byNumber[source.number] = source
        byKey.putIfAbsent(source.type to source.id, source.number)
    }

    /** 登记一条来源，返回它的编号。 */
    fun add(type: EntityType, id: UUID, label: String, at: Instant): Int {
        byKey[type.wireName to id]?.let { return it }
        val n = (byNumber.keys.maxOrNull() ?: 0) + 1
        put(SummarySource(n, type.wireName, id, label, at))
        return n
    }

    operator fun get(number: Int): SummarySource? = byNumber[number]

    val all: List<SummarySource> get() = byNumber.values.toList()
}
