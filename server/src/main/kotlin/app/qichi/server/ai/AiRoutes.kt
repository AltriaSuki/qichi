package app.qichi.server.ai

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.AiChatRequest
import app.qichi.shared.api.QuestionSuggestRequest
import app.qichi.shared.model.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.YearMonth
import java.time.format.DateTimeParseException

/** AI（第 4 阶段）的路由：请求一律 202，结果异步写进实体。 */
fun Route.aiRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        post("/rooms/{roomId}/ai/chat") {
            val accepted = ctx.ai.askInChat(call.user.userId, call.uuidParam("roomId"), call.receive<AiChatRequest>())
            call.respond(HttpStatusCode.Accepted, accepted)
        }
        post("/rooms/{roomId}/ai/question-suggest") {
            call.respond(HttpStatusCode.Accepted, ctx.ai.suggest(call.user.userId, call.uuidParam("roomId"), call.receive<QuestionSuggestRequest>()))
        }
        get("/rooms/{roomId}/ai/jobs/{jobId}") {
            call.respond(ctx.ai.job(call.user.userId, call.uuidParam("roomId"), call.uuidParam("jobId")))
        }
        get("/me/ai-usage") {
            val month = call.request.queryParameters["month"]?.let {
                try {
                    YearMonth.parse(it)
                } catch (_: DateTimeParseException) {
                    throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "month 形如 2026-09")
                }
            }
            call.respond(ctx.ai.usage(call.user.userId, month))
        }
    }
}
