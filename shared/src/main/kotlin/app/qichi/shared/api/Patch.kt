package app.qichi.shared.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * PATCH 请求里的一个字段：要么没发（[Absent]，不改），要么发了一个值（[Value]，值可以是 null，表示清空）。
 *
 * 用法：
 * ```
 * @Serializable
 * data class UpdateRoomRequest(
 *     @EncodeDefault(EncodeDefault.Mode.NEVER) val anniversary: Patch<Day?> = Patch.Absent,
 * )
 * ```
 * JSON 里没有这个键 → Absent；`"anniversary": null` → Value(null)；`"anniversary": "2020-05-20"` → Value(日期)。
 */
@Serializable(with = PatchSerializer::class)
sealed interface Patch<out T> {
    data object Absent : Patch<Nothing>
    data class Value<out T>(val value: T) : Patch<T>

    val isPresent: Boolean get() = this is Value

    fun orNull(): T? = (this as? Value)?.value

    companion object {
        fun <T> of(value: T): Patch<T> = Value(value)
    }
}

/** 有值时执行 [block]。 */
inline fun <T> Patch<T>.ifPresent(block: (T) -> Unit) {
    if (this is Patch.Value) block(value)
}

class PatchSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<Patch<T>> {
    override val descriptor: SerialDescriptor = valueSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Patch<T>) {
        when (value) {
            is Patch.Value -> encoder.encodeSerializableValue(valueSerializer, value.value)
            Patch.Absent -> error("Patch.Absent 不应被序列化；字段上要加 @EncodeDefault(EncodeDefault.Mode.NEVER)")
        }
    }

    override fun deserialize(decoder: Decoder): Patch<T> = Patch.Value(decoder.decodeSerializableValue(valueSerializer))
}
