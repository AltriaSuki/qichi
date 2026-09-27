package app.qichi.server.system

import app.qichi.server.AppContext
import app.qichi.server.plugins.ApiException
import app.qichi.shared.api.Health
import app.qichi.shared.model.ProblemCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.systemRoutes(ctx: AppContext) {
    /** 部署脚本、监控用：服务在、数据库也能用才算正常（Q12）。 */
    get("/health") {
        if (!withContext(Dispatchers.IO) { ctx.database.ping() }) throw ApiException(ProblemCode.InternalError, "数据库连不上")
        call.respond(Health(status = "ok", version = ctx.buildInfo.version))
    }
}
