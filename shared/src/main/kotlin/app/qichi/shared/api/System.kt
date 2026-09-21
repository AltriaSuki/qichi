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

/** 请求头：客户端版本，如 android/0.1.0。 */
const val CLIENT_HEADER: String = "X-Qichi-Client"
