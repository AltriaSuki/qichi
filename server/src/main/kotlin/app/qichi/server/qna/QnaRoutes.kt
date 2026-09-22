package app.qichi.server.qna

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateQuestionRequest
import app.qichi.shared.api.WriteAnswerRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.qnaRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}") {
            get("/qna/today") { call.respond(ctx.qna.today(call.user.userId, call.uuidParam("roomId"))) }
            put("/qna/rounds/{roundId}/answer") {
                call.respondCreated(ctx.qna.answer(call.user.userId, call.uuidParam("roomId"), call.uuidParam("roundId"), call.receive<WriteAnswerRequest>()))
            }
            post("/qna/rounds/{roundId}/confirm") {
                call.respond(ctx.qna.confirm(call.user.userId, call.uuidParam("roomId"), call.uuidParam("roundId")))
            }
            get("/questions") {
                call.respond(ctx.qna.questions(call.user.userId, call.uuidParam("roomId"), call.request.queryParameters["status"] ?: "adopted"))
            }
            post("/questions") {
                call.respondCreated(ctx.qna.createQuestion(call.user.userId, call.uuidParam("roomId"), call.receive<CreateQuestionRequest>()))
            }
            post("/questions/{id}/adopt") {
                call.respond(ctx.qna.adopt(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
            delete("/questions/{id}") {
                call.respond(ctx.qna.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
        }
    }
}
