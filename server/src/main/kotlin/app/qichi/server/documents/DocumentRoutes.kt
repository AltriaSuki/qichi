package app.qichi.server.documents

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.intQuery
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateDocumentRequest
import app.qichi.shared.api.SaveDocumentVersionRequest
import app.qichi.shared.api.UpdateDocumentRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.documentRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/documents") {
            get { call.respond(ctx.documents.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.documents.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateDocumentRequest>())) }
            route("/{id}") {
                patch {
                    call.respond(ctx.documents.rename(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateDocumentRequest>()))
                }
                delete { call.respond(ctx.documents.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
                get("/versions") {
                    val limit = call.intQuery("limit") ?: Limits.CURSOR_PAGE_DEFAULT
                    call.respond(ctx.documents.versions(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.request.queryParameters["cursor"], limit))
                }
                post("/versions") {
                    call.respondCreated(
                        ctx.documents.save(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<SaveDocumentVersionRequest>()),
                    )
                }
                get("/versions/{version}") {
                    val version = call.parameters["version"]?.toIntOrNull()
                        ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "version 不合法")
                    call.respond(ctx.documents.version(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), version))
                }
            }
        }
    }
}
