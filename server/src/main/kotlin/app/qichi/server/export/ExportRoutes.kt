package app.qichi.server.export

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate

fun Route.exportRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        get("/rooms/{roomId}/export") {
            val includeFiles = call.request.queryParameters["files"] == "true"
            val roomId = call.uuidParam("roomId")
            // 先确认能导出（不是成员就 404），再开始边读边写
            ctx.export.checkAccess(call.user.userId, roomId)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "qichi-export-${LocalDate.now()}.zip").toString(),
            )
            call.respondOutputStream(ContentType.parse("application/zip")) {
                ctx.export.export(call.user.userId, roomId, includeFiles, this)
            }
        }
    }
}
