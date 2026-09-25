package app.qichi.server.tags

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.RenameTagRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.tagRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        post("/rooms/{roomId}/tags/rename") {
            call.respond(ctx.tags.rename(call.user.userId, call.uuidParam("roomId"), call.receive<RenameTagRequest>()))
        }
    }
}
