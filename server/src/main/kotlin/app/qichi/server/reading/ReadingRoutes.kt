package app.qichi.server.reading

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CreateBookRequest
import app.qichi.shared.api.CreateHighlightRequest
import app.qichi.shared.api.PutReadingProgressRequest
import app.qichi.shared.api.UpdateBookRequest
import app.qichi.shared.api.UpdateHighlightRequest
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

fun Route.readingRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/books") {
            get { call.respond(ctx.reading.books(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.reading.createBook(call.user.userId, call.uuidParam("roomId"), call.receive<CreateBookRequest>())) }
            patch("/{id}") {
                call.respond(ctx.reading.updateBook(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateBookRequest>()))
            }
            delete("/{id}") { call.respond(ctx.reading.deleteBook(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            put("/{bookId}/progress") {
                call.respond(ctx.reading.putProgress(call.user.userId, call.uuidParam("roomId"), call.uuidParam("bookId"), call.receive<PutReadingProgressRequest>()))
            }
            post("/{bookId}/highlights") {
                call.respondCreated(ctx.reading.createHighlight(call.user.userId, call.uuidParam("roomId"), call.uuidParam("bookId"), call.receive<CreateHighlightRequest>()))
            }
            patch("/{bookId}/highlights/{id}") {
                call.respond(ctx.reading.updateHighlight(call.user.userId, call.uuidParam("roomId"), call.uuidParam("bookId"), call.uuidParam("id"), call.receive<UpdateHighlightRequest>()))
            }
            delete("/{bookId}/highlights/{id}") {
                call.respond(ctx.reading.deleteHighlight(call.user.userId, call.uuidParam("roomId"), call.uuidParam("bookId"), call.uuidParam("id")))
            }
        }
    }
}
