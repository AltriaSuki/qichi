package app.qichi.shared.api

import app.qichi.shared.model.ProblemCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** 错误响应（application/problem+json，RFC 9457）。客户端按 [code] 判断，不看 [title]。可选字段为空时不输出。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val code: ProblemCode,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val detail: String? = null,
    /** 仅 invalid_request：具体哪些字段不合法。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val errors: List<FieldError>? = null,
    /** 仅 conflict_version：当前最新版本号。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val latestVersion: Int? = null,
)

@Serializable
data class FieldError(
    val field: String,
    val message: String,
)

const val PROBLEM_CONTENT_TYPE: String = "application/problem+json"
