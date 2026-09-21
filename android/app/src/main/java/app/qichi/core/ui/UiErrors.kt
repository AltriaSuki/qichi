package app.qichi.core.ui

import app.qichi.core.network.ApiException
import app.qichi.core.network.NetworkException
import app.qichi.core.network.SessionExpiredException

/** 一次提交失败后界面要显示的内容：字段级错误 + 一句总的提示。 */
data class FormError(
    val fields: Map<String, String> = emptyMap(),
    val message: String? = null,
) {
    operator fun get(field: String): String? = fields[field]
}

/** 把异常转成给人看的提示。 */
fun Throwable.toFormError(): FormError = when (this) {
    is ApiException -> {
        val fields = problem?.errors.orEmpty().associate { it.field to it.message }
        FormError(fields, if (fields.isEmpty()) userMessage else null)
    }
    is NetworkException -> FormError(message = "连不上服务器，请检查网络")
    is SessionExpiredException -> FormError(message = "登录已失效，请重新登录")
    else -> FormError(message = "出了点问题，请稍后再试")
}
