package app.qichi.shared.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer

/** 枚举在 JSON 与数据库里的字符串（即 @SerialName），如 `MoodLabel.Tired.wireName == "tired"`。 */
@OptIn(ExperimentalSerializationApi::class)
inline val <reified E : Enum<E>> E.wireName: String
    get() = serializer<E>().descriptor.getElementName(ordinal)

/** 由字符串找回枚举；不认识的值返回 null。 */
inline fun <reified E : Enum<E>> fromWireOrNull(wire: String): E? =
    enumValues<E>().firstOrNull { it.wireName == wire }

/** 由字符串找回枚举；不认识的值抛出 IllegalArgumentException。 */
inline fun <reified E : Enum<E>> fromWire(wire: String): E =
    fromWireOrNull<E>(wire) ?: throw IllegalArgumentException("未知的 ${E::class.simpleName}：$wire")
