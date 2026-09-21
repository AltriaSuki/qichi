package app.qichi.server.auth

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.clientIp
import app.qichi.server.plugins.user
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.RefreshRequest
import app.qichi.shared.api.RegisterRequest
import app.qichi.shared.api.UpdateMeRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(ctx: AppContext) {
    route("/auth") {
        post("/register") {
            val registered = ctx.auth.register(call.receive<RegisterRequest>(), call.clientIp)
            call.respond(HttpStatusCode.Created, registered.tokens)
        }
        post("/login") {
            call.respond(ctx.auth.login(call.receive<LoginRequest>()))
        }
        post("/refresh") {
            call.respond(ctx.auth.refresh(call.receive<RefreshRequest>().refreshToken))
        }
        authenticate(AUTH_JWT) {
            post("/logout") {
                // 请求体里的刷新令牌不必须：以访问令牌所属的登录为准
                ctx.auth.logout(call.user)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    authenticate(AUTH_JWT) {
        route("/me") {
            get {
                call.respond(ctx.me.get(call.user.userId))
            }
            patch {
                call.respond(ctx.me.update(call.user.userId, call.receive<UpdateMeRequest>()))
            }
            post("/password") {
                call.respond(ctx.auth.changePassword(call.user, call.receive<ChangePasswordRequest>()))
            }
        }
    }
}
