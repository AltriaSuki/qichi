package app.qichi.server.ideas

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateIdeaRequest
import app.qichi.shared.api.UpdateIdeaRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.ideaRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/ideas") {
            post { call.respondCreated(ctx.ideas.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateIdeaRequest>())) }
            patch("/{id}") {
                call.respond(ctx.ideas.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateIdeaRequest>()))
            }
            delete("/{id}") { call.respond(ctx.ideas.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
        }
    }
}
