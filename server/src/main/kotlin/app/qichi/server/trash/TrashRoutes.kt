package app.qichi.server.trash

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.intQuery
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.model.TrashType
import app.qichi.shared.model.fromWireOrNull
import app.qichi.shared.rules.Limits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.trashRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/trash") {
            get {
                val limit = call.intQuery("limit") ?: Limits.CURSOR_PAGE_DEFAULT
                call.respond(ctx.trash.list(call.user.userId, call.uuidParam("roomId"), call.request.queryParameters["cursor"], limit))
            }
            delete("/{type}/{id}") {
                ctx.trash.purge(call.user.userId, call.uuidParam("roomId"), call.trashType(), call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("/{type}/{id}/restore") {
                call.respond(ctx.trash.restore(call.user.userId, call.uuidParam("roomId"), call.trashType(), call.uuidParam("id")))
            }
        }
    }
}

/** 路径里的类型：不认识的当作不存在（404）。 */
private fun ApplicationCall.trashType(): TrashType =
    parameters["type"]?.let { fromWireOrNull<TrashType>(it) } ?: notFound()
