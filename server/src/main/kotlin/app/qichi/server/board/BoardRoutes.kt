package app.qichi.server.board

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateBoardPostRequest
import app.qichi.shared.api.CreateBoardTopicRequest
import app.qichi.shared.api.PutBoardReactionRequest
import app.qichi.shared.api.ReviseBoardPostRequest
import app.qichi.shared.api.UpdateBoardTopicRequest
import app.qichi.shared.model.BoardReactionKind
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.fromWireOrNull
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.boardRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/board") {
            route("/topics") {
                get { call.respond(ctx.board.topics(call.user.userId, call.uuidParam("roomId"))) }
                post { call.respondCreated(ctx.board.createTopic(call.user.userId, call.uuidParam("roomId"), call.receive<CreateBoardTopicRequest>())) }
                patch("/{id}") {
                    call.respond(ctx.board.updateTopic(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateBoardTopicRequest>()))
                }
                delete("/{id}") { call.respond(ctx.board.deleteTopic(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
                post("/{topicId}/posts") {
                    call.respondCreated(
                        ctx.board.createPost(call.user.userId, call.uuidParam("roomId"), call.uuidParam("topicId"), call.receive<CreateBoardPostRequest>()),
                    )
                }
            }
            route("/posts/{id}") {
                patch {
                    call.respond(ctx.board.revise(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<ReviseBoardPostRequest>()))
                }
                delete { call.respond(ctx.board.deletePost(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
                get("/revisions") { call.respond(ctx.board.revisions(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
                put("/reactions/{kind}") {
                    call.respondCreated(
                        ctx.board.react(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.reactionKind(), call.receive<PutBoardReactionRequest>()),
                    )
                }
                delete("/reactions/{kind}") {
                    call.respond(ctx.board.unreact(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.reactionKind()))
                }
            }
            get("/search") { call.respond(ctx.board.search(call.user.userId, call.uuidParam("roomId"), call.request.queryParameters["q"])) }
        }
    }
}

private fun ApplicationCall.reactionKind(): BoardReactionKind =
    parameters["kind"]?.let { fromWireOrNull<BoardReactionKind>(it) }
        ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "不认识的回应")
