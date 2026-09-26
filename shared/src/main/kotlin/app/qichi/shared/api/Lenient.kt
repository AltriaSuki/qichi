package app.qichi.shared.api

import app.qichi.shared.model.ChangeOp
import app.qichi.shared.model.EntityType
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 解码结果：[value] 是认得出来的部分。
 * - [skipped]：认不出来、只好跳过的实体数（新的实体类型、新的枚举取值）
 * - [partial]：认得出来、但带着不认识字段的实体数（新字段先丢掉）
 */
data class Decoded<T>(val value: T, val skipped: Int = 0, val partial: Int = 0) {
    /** 有新版 App 才完全懂的内容：升级后应该重新快照 */
    val incomplete: Boolean get() = skipped > 0 || partial > 0
}

/** 同步一页里认得出来的一条变化：upsert 时 [entity] 是解好的实体，delete 时为 null。 */
data class DecodedChange(val seq: Long, val type: EntityType, val id: Id, val op: ChangeOp, val entity: Any?)

/** 逐条解码后的同步一页（对应 [SyncResponse]）。 */
data class DecodedSyncPage(val fromSeq: Long, val toSeq: Long, val hasMore: Boolean, val changes: List<DecodedChange>)

/**
 * 旧版 App 读新版服务端的数据（P13-07，docs/05-sync-offline.md §3.2）。
 *
 * 服务端先部署、App 还没更新时，数据里会有 App 不认识的东西：新的实体类型、枚举取值、字段。
 * 整页一起解码的话，一条认不出来整页都失败，同步位置永远停住。这里改成一个实体一个实体地解：
 * 认不出来的跳过、认得出来的照常用，并告诉调用方跳过了多少，App 记下来，升级后重新快照补回来。
 */
object Lenient {
    /** 和 [QichiJson] 一样，只是遇到不认识的字段就报错：用来发现「服务端比 App 新、多给了字段」。 */
    private val strict = Json(from = QichiJson) { ignoreUnknownKeys = false }

    /** 数据类的 serialName → 实体类型：在快照、消息页这样的容器里认出哪些字段装的是实体。 */
    private val entityTypes: Map<String, EntityType> by lazy {
        EntityType.entries.associateBy { EntityCodec.serializer(it).descriptor.serialName }
    }

    @Serializable
    private class RawSyncResponse(val fromSeq: Long, val toSeq: Long, val hasMore: Boolean, val changes: List<JsonElement>)

    /** 同步一页：页本身（seq、hasMore）必须认得；每条变化单独解，认不出来的跳过。 */
    fun syncPage(json: JsonElement): Decoded<DecodedSyncPage> {
        val raw = QichiJson.decodeFromJsonElement(RawSyncResponse.serializer(), json)
        var skipped = 0
        var partial = 0
        val changes = raw.changes.mapNotNull { element ->
            val change = attempt(Change.serializer(), element).value
            val entity = when {
                change == null -> null
                change.op == ChangeOp.Delete -> return@mapNotNull DecodedChange(change.seq, change.type, change.id, change.op, null)
                else -> change.data?.takeIf { it !is JsonNull }?.let { attempt(EntityCodec.serializer(change.type), it) }
            }
            if (change == null || entity?.value == null) {
                skipped++
                return@mapNotNull null
            }
            if (entity.partial) partial++
            DecodedChange(change.seq, change.type, change.id, change.op, entity.value)
        }
        return Decoded(DecodedSyncPage(raw.fromSeq, raw.toSeq, raw.hasMore, changes), skipped, partial)
    }

    /**
     * 装着实体的对象（快照 [Bootstrap]、一页消息 [MessagePage]……）：里面的实体逐个解，认不出来的去掉；
     * 这个版本还没有的整个列表（例如快照里新加的一类实体）也算跳过。
     * 必填的单个实体（快照里的房间）认不出来时整个失败——那不是新旧版本的问题。
     */
    fun <T> container(deserializer: DeserializationStrategy<T>, json: JsonElement): Decoded<T> {
        val descriptor = deserializer.descriptor
        var skipped = 0
        var partial = 0
        fun keep(type: EntityType, element: JsonElement): Boolean {
            val result = attempt(EntityCodec.serializer(type), element)
            if (result.value == null) skipped++ else if (result.partial) partial++
            return result.value != null
        }
        val cleaned = buildMap {
            for ((key, value) in json.jsonObject) {
                val index = descriptor.getElementIndex(key)
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    if (value is JsonArray) skipped += value.size
                    continue
                }
                val element = descriptor.getElementDescriptor(index)
                val listType = if (element.kind == StructureKind.LIST) entityTypeOf(element.getElementDescriptor(0)) else null
                val singleType = entityTypeOf(element)
                when {
                    listType != null && value is JsonArray -> put(key, JsonArray(value.filter { keep(listType, it) }))
                    singleType != null && value !is JsonNull -> put(key, if (keep(singleType, value) || !element.isNullable) value else JsonNull)
                    else -> put(key, value)
                }
            }
        }
        return Decoded(QichiJson.decodeFromJsonElement(deserializer, JsonObject(cleaned)), skipped, partial)
    }

    private fun entityTypeOf(descriptor: SerialDescriptor): EntityType? = entityTypes[descriptor.serialName.removeSuffix("?")]

    private class Attempt<T>(val value: T?, val partial: Boolean)

    /** 先严格解；不行再忽略不认识的字段解（partial）；还不行就是认不出来（value = null）。 */
    private fun <T> attempt(deserializer: DeserializationStrategy<T>, json: JsonElement): Attempt<T> {
        try {
            return Attempt(strict.decodeFromJsonElement(deserializer, json), partial = false)
        } catch (_: Exception) {
        }
        return try {
            Attempt(QichiJson.decodeFromJsonElement(deserializer, json), partial = true)
        } catch (_: Exception) {
            Attempt(null, partial = false)
        }
    }
}
