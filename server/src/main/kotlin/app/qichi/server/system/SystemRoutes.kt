package app.qichi.server.system

import app.qichi.server.AppContext
import app.qichi.shared.api.Health
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.systemRoutes(ctx: AppContext) {
    get("/health") {
        call.respond(Health(status = "ok", version = ctx.buildInfo.version))
    }
}
