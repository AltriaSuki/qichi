package app.qichi.shared.api

import kotlinx.serialization.Serializable

/** GET /health */
@Serializable
data class Health(
    val status: String,
    val version: String,
)

/** 所有接口的路径前缀。 */
const val API_PREFIX: String = "/api/v1"

/** 请求头：客户端版本，如 `android/0.2.345 (345)`（见 [androidClientHeader]）。 */
const val CLIENT_HEADER: String = "X-Qichi-Client"

/** App 发的 [CLIENT_HEADER]：`android/{versionName} ({versionCode})`。服务端据 versionCode 判断 App 是否太旧（P13-07）。 */
fun androidClientHeader(versionName: String, versionCode: Int): String = "android/$versionName ($versionCode)"

/**
 * 从 [CLIENT_HEADER] 读出 Android App 的 versionCode；不是 Android App、或读不出来时为 null。
 * P13-07 之前的 App 只发 `android/0.2.{提交数}`，那时 versionCode 就是提交数，从 versionName 的最后一段读。
 */
fun androidVersionCodeOf(header: String?): Int? {
    val value = header?.trim()?.takeIf { it.startsWith("android/") } ?: return null
    NEW_FORMAT.matchEntire(value)?.let { return it.groupValues[1].toIntOrNull() }
    return OLD_FORMAT.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
}

private val NEW_FORMAT = Regex("""android/\S+ \((\d+)\)""")
private val OLD_FORMAT = Regex("""android/\d+\.\d+\.(\d+)""")
