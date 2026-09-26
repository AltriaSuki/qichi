package app.qichi.server.ai.tools

import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 模型给的参数不对：把原因当作工具结果告诉它，让它改了再查。 */
internal class BadArgs(message: String) : Exception(message)

/** 模型给的参数（JSON 对象）。 */
internal class Args(private val obj: JsonObject) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Args = Args(
            runCatching { json.parseToJsonElement(text.ifBlank { "{}" }) as JsonObject }.getOrElse { throw BadArgs("参数不是合法的 JSON 对象") },
        )
    }

    fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun int(name: String): Int? = (obj[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    fun strings(name: String): List<String> = when (val v = obj[name]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> listOfNotNull(v.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
        else -> emptyList()
    }

    fun date(name: String): LocalDate? = str(name)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            throw BadArgs("$name 要写成 YYYY-MM-DD，收到的是「$it」")
        }
    }

    fun requireDate(name: String): LocalDate = date(name) ?: throw BadArgs("缺少 $name（YYYY-MM-DD）")

    fun choice(name: String, allowed: List<String>, default: String): String {
        val v = str(name)?.lowercase() ?: return default
        if (v !in allowed) throw BadArgs("$name 只能是 ${allowed.joinToString("、")}，收到的是「$v」")
        return v
    }
}

/**
 * 工具结果：一行一行加，总长超过 [budget] 后不再加，只记还有多少条没列出。
 * 每条记录的编号只在真的放进结果时才登记（[item]）。
 */
internal class Out(private val book: SourceBook, private val budget: Int = RESULT_MAX) {
    private val sb = StringBuilder()
    private var omitted = 0
    val full: Boolean get() = sb.length >= budget

    /** 不带编号的一行（标题、说明、子项）。放不下返回 false。 */
    fun text(line: String): Boolean {
        if (sb.length + line.length + 1 > budget) {
            omitted++
            return false
        }
        sb.append(line).append('\n')
        return true
    }

    /** 一条可以引用的记录：放得下才登记编号，行首是「[n] 」。 */
    fun item(type: app.qichi.shared.model.EntityType, id: java.util.UUID, label: String, at: java.time.Instant, line: String): Boolean {
        // 编号最多几位数，先按最长的算够不够放
        if (sb.length + line.length + 8 > budget) {
            omitted++
            return false
        }
        val n = book.add(type, id, label, at)
        sb.append('[').append(n).append("] ").append(line).append('\n')
        return true
    }

    /** 已经知道还有多少条没查出来（数据库里就截断了）。 */
    fun more(count: Int) {
        omitted += count
    }

    fun result(empty: String): String {
        if (sb.isEmpty() && omitted == 0) return empty
        if (omitted > 0) sb.append("（还有 $omitted 条没列出：可以缩小日期范围、换关键词，或用编号看详情）")
        return sb.toString().trimEnd()
    }

    companion object {
        /** 单次工具结果最多多少字 */
        const val RESULT_MAX = 4000
    }
}

// ── 参数说明（JSON Schema）──

internal fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
}

internal fun strParam(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
}

internal fun intParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun dateParam(description: String) = strParam("$description，写成 YYYY-MM-DD")

internal fun refParam(what: String) = intParam("要看详情的$what 的编号（结果里的 [n]）")

internal fun listParam(description: String, enum: List<String>): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "string")
        putJsonArray("enum") { enum.forEach { add(it) } }
    }
}
