package app.qichi.core.network

import app.qichi.shared.api.Problem
import app.qichi.shared.model.ProblemCode
import java.io.IOException

/** 服务端返回了错误（problem+json）。按 [code] 判断，不看 title。 */
class ApiException(
    val status: Int,
    val problem: Problem?,
) : Exception(problem?.let { "${it.code}: ${it.title}" } ?: "HTTP $status") {
    val code: ProblemCode? get() = problem?.code

    /** 给用户看的一句话（服务端写好的中文标题）。 */
    val userMessage: String get() = problem?.title ?: "出了点问题，请稍后再试"

    /** 可以稍后重试的错误：服务器出错、限流。 */
    val isRetryable: Boolean get() = status >= 500 || status == 429

    /** 字段级错误（invalid_request）。 */
    fun fieldError(field: String): String? = problem?.errors?.firstOrNull { it.field == field }?.message
}

/** 连不上服务器、超时等网络问题。 */
class NetworkException(cause: Throwable) : IOException("网络不可用：${cause.message}", cause)

/** 登录已失效（刷新令牌也不能用了），需要重新登录。 */
class SessionExpiredException : IOException("登录已失效")
