package app.qichi.server.plugins

import app.qichi.shared.api.FieldError
import app.qichi.shared.model.ProblemCode

/**
 * 收集请求参数里所有不合法的字段，一次性以 400 invalid_request 返回（errors 列出每个字段）。
 * ```
 * validate {
 *     check(req.name.length in 1..40, "name", "房间名 1–40 个字")
 * }
 * ```
 */
class Validator {
    private val errors = mutableListOf<FieldError>()

    fun check(condition: Boolean, field: String, message: String) {
        if (!condition) errors += FieldError(field, message)
    }

    fun fail(field: String, message: String) {
        errors += FieldError(field, message)
    }

    @PublishedApi
    internal fun throwIfInvalid() {
        if (errors.isNotEmpty()) {
            throw ApiException(
                ProblemCode.InvalidRequest,
                "请求参数不合法",
                detail = errors.joinToString("；") { "${it.field}：${it.message}" },
                errors = errors.toList(),
            )
        }
    }
}

inline fun validate(block: Validator.() -> Unit) {
    Validator().apply(block).throwIfInvalid()
}

fun notFound(): Nothing = throw ApiException(ProblemCode.NotFound, "找不到")

fun forbidden(title: String = "没有权限做这件事"): Nothing = throw ApiException(ProblemCode.Forbidden, title)
