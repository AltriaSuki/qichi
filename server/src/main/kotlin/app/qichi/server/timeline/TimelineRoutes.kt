package app.qichi.server.timeline

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.intQuery
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.timelineRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        get("/rooms/{roomId}/on-this-day") {
            val date = call.request.queryParameters["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?: throw app.qichi.server.plugins.ApiException(app.qichi.shared.model.ProblemCode.InvalidRequest, "请求参数不合法", detail = "date 形如 2025-09-23")
            call.respond(ctx.timeline.onThisDay(call.user.userId, call.uuidParam("roomId"), date))
        }
        route("/rooms/{roomId}/timeline") {
            get { call.respond(ctx.timeline.month(call.user.userId, call.uuidParam("roomId"), call.intQuery("year"), call.intQuery("month"))) }
            get("/picks") { call.respond(ctx.timeline.picks(call.user.userId, call.uuidParam("roomId"))) }
            put("/picks/{fileId}") {
                ctx.timeline.pick(call.user.userId, call.uuidParam("roomId"), call.uuidParam("fileId"))
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/picks/{fileId}") {
                ctx.timeline.unpick(call.user.userId, call.uuidParam("roomId"), call.uuidParam("fileId"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
