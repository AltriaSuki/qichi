package app.qichi.server.plugins

import app.qichi.shared.api.FieldError
import app.qichi.shared.api.PROBLEM_CONTENT_TYPE
import app.qichi.shared.api.Problem
import app.qichi.shared.api.QichiJson
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.wireName
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * 业务错误：在任何地方抛出，统一转成 problem+json。
 * [title] 是给人看的中文标题；客户端只按 [code] 判断。
 */
class ApiException(
    val code: ProblemCode,
    val title: String,
    val detail: String? = null,
    val status: HttpStatusCode = code.defaultStatus(),
    val errors: List<FieldError>? = null,
    val latestVersion: Int? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException("${code.wireName}: $title")

fun ProblemCode.defaultStatus(): HttpStatusCode = when (this) {
    ProblemCode.InvalidRequest, ProblemCode.InviteInvalid -> HttpStatusCode.BadRequest
    ProblemCode.Unauthorized -> HttpStatusCode.Unauthorized
    ProblemCode.Forbidden, ProblemCode.RegistrationClosed -> HttpStatusCode.Forbidden
    ProblemCode.NotFound -> HttpStatusCode.NotFound
    ProblemCode.ConflictVersion, ProblemCode.ConflictId, ProblemCode.RoomFull, ProblemCode.UsernameTaken ->
        HttpStatusCode.Conflict
    ProblemCode.PayloadTooLarge -> HttpStatusCode.PayloadTooLarge
    ProblemCode.UnsupportedMediaType -> HttpStatusCode.UnsupportedMediaType
    ProblemCode.RateLimited, ProblemCode.AiQuotaExceeded -> HttpStatusCode.TooManyRequests
    ProblemCode.AiUnavailable -> HttpStatusCode.ServiceUnavailable
    ProblemCode.InternalError -> HttpStatusCode.InternalServerError
}

private val log = LoggerFactory.getLogger("app.qichi.server.errors")

suspend fun ApplicationCall.respondProblem(e: ApiException) {
    e.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    val problem = Problem(
        type = "https://qichi.app/errors/${e.code.wireName}",
        title = e.title,
        status = e.status.value,
        code = e.code,
        detail = e.detail,
        errors = e.errors,
        latestVersion = e.latestVersion,
    )
    respondText(
        text = QichiJson.encodeToString(Problem.serializer(), problem),
        contentType = ContentType.parse(PROBLEM_CONTENT_TYPE),
        status = e.status,
    )
}

fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondProblem(e) }

        // 请求体不是合法 JSON、缺字段、类型不对
        exception<ContentTransformationException> { call, e ->
            call.respondProblem(ApiException(ProblemCode.InvalidRequest, "请求格式不正确", e.message))
        }
        exception<SerializationException> { call, e ->
            call.respondProblem(ApiException(ProblemCode.InvalidRequest, "请求格式不正确", e.message))
        }
        exception<BadRequestException> { call, e ->
            val detail = generateSequence(e as Throwable) { it.cause }.last().message
            call.respondProblem(ApiException(ProblemCode.InvalidRequest, "请求格式不正确", detail))
        }
        exception<NotFoundException> { call, _ ->
            call.respondProblem(ApiException(ProblemCode.NotFound, "找不到"))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondProblem(ApiException(ProblemCode.PayloadTooLarge, "内容太大"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondProblem(ApiException(ProblemCode.UnsupportedMediaType, "不支持的内容类型"))
        }
        exception<Throwable> { call, e ->
            log.error("未处理的错误：{} {}", call.request.httpMethod.value, call.safePath(), e)
            call.respondProblem(ApiException(ProblemCode.InternalError, "服务器出错了"))
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondProblem(ApiException(ProblemCode.NotFound, "找不到"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(ApiException(ProblemCode.InvalidRequest, "不支持这个请求方法", status = status))
        }
        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            call.respondProblem(ApiException(ProblemCode.UnsupportedMediaType, "不支持的内容类型"))
        }
    }
}
