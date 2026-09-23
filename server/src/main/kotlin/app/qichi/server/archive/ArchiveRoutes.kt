package app.qichi.server.archive

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateArchiveItemRequest
import app.qichi.shared.api.ReviseArchiveItemRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.archiveRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/archive") {
            get { call.respond(ctx.archive.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.archive.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateArchiveItemRequest>())) }
            delete("/{id}") { call.respond(ctx.archive.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            get("/{id}/revisions") { call.respond(ctx.archive.revisions(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            post("/{id}/revisions") {
                call.respondCreated(ctx.archive.revise(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<ReviseArchiveItemRequest>()))
            }
        }
    }
}
