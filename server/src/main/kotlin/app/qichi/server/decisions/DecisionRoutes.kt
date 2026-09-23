package app.qichi.server.decisions

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateDecisionRequest
import app.qichi.shared.api.UpdateDecisionRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.decisionRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/decisions") {
            get { call.respond(ctx.decisions.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.decisions.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateDecisionRequest>())) }
            patch("/{id}") {
                call.respond(ctx.decisions.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateDecisionRequest>()))
            }
            delete("/{id}") { call.respond(ctx.decisions.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
        }
    }
}
