package app.qichi.server.review

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateAnnotationReplyRequest
import app.qichi.shared.api.CreateAnnotationRequest
import app.qichi.shared.api.ConvertFindingRequest
import app.qichi.shared.api.CreateReviewRequest
import app.qichi.shared.api.CreateReviewVersionRequest
import app.qichi.shared.api.UpdateAnnotationRequest
import app.qichi.shared.api.UpdateReviewRequest
import app.qichi.shared.model.ProblemCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private fun ApplicationCall.intParam(name: String, source: Map<String, String?>): Int =
    source[name]?.toIntOrNull()?.takeIf { it >= 1 }
        ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "$name 要是从 1 开始的整数")

fun Route.reviewRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/reviews") {
            get { call.respond(ctx.reviews.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.reviews.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateReviewRequest>())) }
            patch("/{id}") {
                call.respond(ctx.reviews.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateReviewRequest>()))
            }
            delete("/{id}") { call.respond(ctx.reviews.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            post("/{id}/versions") {
                call.respondCreated(ctx.reviews.createVersion(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<CreateReviewVersionRequest>()))
            }
            get("/{id}/versions/{version}/pages") {
                val number = call.intParam("version", mapOf("version" to call.parameters["version"]))
                call.respond(ctx.reviews.pages(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), number))
            }
            get("/{id}/diff") {
                val q = call.request.queryParameters
                val from = call.intParam("from", mapOf("from" to q["from"]))
                val to = call.intParam("to", mapOf("to" to q["to"]))
                call.respond(ctx.reviews.diff(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), from, to))
            }
            post("/{id}/annotations") {
                call.respondCreated(ctx.reviews.createAnnotation(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<CreateAnnotationRequest>()))
            }
            patch("/{id}/annotations/{annId}") {
                call.respond(
                    ctx.reviews.updateAnnotation(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.uuidParam("annId"), call.receive<UpdateAnnotationRequest>()),
                )
            }
            delete("/{id}/annotations/{annId}") {
                call.respond(ctx.reviews.deleteAnnotation(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.uuidParam("annId")))
            }
        }
        post("/rooms/{roomId}/ai-findings/{id}/convert") {
            call.respondCreated(ctx.reviews.convertFinding(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<ConvertFindingRequest>()))
        }
        post("/rooms/{roomId}/ai-findings/{id}/dismiss") {
            call.respond(ctx.reviews.dismissFinding(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
        }
        post("/rooms/{roomId}/annotations/{annId}/replies") {
            call.respondCreated(ctx.reviews.createReply(call.user.userId, call.uuidParam("roomId"), call.uuidParam("annId"), call.receive<CreateAnnotationReplyRequest>()))
        }
    }
}
