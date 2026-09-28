package app.qichi.server.rooms

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.AcceptInviteRequest
import app.qichi.shared.api.CreateRoomRequest
import app.qichi.shared.api.UpdateRoomRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.roomRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        post("/rooms") {
            val (detail, created) = ctx.rooms.create(call.user.userId, call.receive<CreateRoomRequest>())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, detail)
        }
        route("/rooms/{roomId}") {
            get {
                call.respond(ctx.rooms.get(call.user.userId, call.uuidParam("roomId")))
            }
            patch {
                call.respond(ctx.rooms.update(call.user.userId, call.uuidParam("roomId"), call.receive<UpdateRoomRequest>()))
            }
            // 退出房间（P16-07）：房间和内容留给另一个人
            post("/leave") {
                ctx.rooms.leave(call.user.userId, call.uuidParam("roomId"))
                call.respond(HttpStatusCode.NoContent)
            }
            post("/invites") {
                call.respond(HttpStatusCode.Created, ctx.rooms.createInvite(call.user.userId, call.uuidParam("roomId")))
            }
            // 给忘了密码的另一个人生成重置码（P16-04）
            post("/members/{userId}/password-reset") {
                call.respond(HttpStatusCode.Created, ctx.auth.createResetCode(call.user.userId, call.uuidParam("roomId"), call.uuidParam("userId")))
            }
        }
        post("/invites/accept") {
            call.respond(ctx.rooms.acceptInvite(call.user.userId, call.receive<AcceptInviteRequest>().code))
        }
    }
}
