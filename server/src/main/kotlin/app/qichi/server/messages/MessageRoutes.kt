package app.qichi.server.messages

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.intQuery
import app.qichi.server.plugins.longQuery
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateReadMarkerRequest
import app.qichi.shared.rules.Limits
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** 聊天（第 3 阶段）的路由。 */
fun Route.messageRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/messages") {
            get {
                val beforeSeq = call.longQuery("beforeSeq")
                val limit = call.intQuery("limit") ?: Limits.MESSAGE_PAGE_DEFAULT
                call.respond(ctx.messages.history(call.user.userId, call.uuidParam("roomId"), beforeSeq, limit))
            }
            post {
                call.respondCreated(ctx.messages.send(call.user.userId, call.uuidParam("roomId"), call.receive<SendMessageRequest>()))
            }
            get("/search") {
                val q = call.request.queryParameters["q"]
                val limit = call.intQuery("limit") ?: Limits.CURSOR_PAGE_DEFAULT
                call.respond(ctx.messages.search(call.user.userId, call.uuidParam("roomId"), q, call.request.queryParameters["cursor"], limit))
            }
            delete("/{id}") {
                call.respond(ctx.messages.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
            post("/{id}/retract") {
                call.respond(ctx.messages.retract(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
        }
        put("/rooms/{roomId}/read-marker") {
            call.respond(ctx.messages.updateReadMarker(call.user.userId, call.uuidParam("roomId"), call.receive<UpdateReadMarkerRequest>()))
        }
    }
}
