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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun Route.exportRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        get("/rooms/{roomId}/export") {
            val includeFiles = call.request.queryParameters["files"] == "true"
            val bundle = ctx.export.bundle(call.user.userId, call.uuidParam("roomId"), includeFiles)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "qichi-export-${LocalDate.now()}.zip").toString(),
            )
            call.respondOutputStream(ContentType.parse("application/zip")) {
                withContext(Dispatchers.IO) { ctx.export.write(bundle, this@respondOutputStream) }
            }
        }
    }
}
