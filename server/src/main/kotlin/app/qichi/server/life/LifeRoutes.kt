package app.qichi.server.life

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CreateEventRequest
import app.qichi.shared.api.CreateMoodReplyRequest
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.UpdateEventRequest
import app.qichi.shared.api.UpdateTodoRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** 创建类接口：新建 201，同 id 已存在 200。 */
suspend inline fun <reified T : Any> ApplicationCall.respondCreated(result: Pair<T, Boolean>) =
    respond(if (result.second) HttpStatusCode.Created else HttpStatusCode.OK, result.first)

/** 心情、待办、日程（第 2 阶段）的路由。 */
fun Route.lifeRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}") {
            // ── 心情 ──
            post("/moods") {
                call.respondCreated(ctx.moods.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateMoodRequest>()))
            }
            delete("/moods/{id}") {
                call.respond(ctx.moods.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
            post("/moods/{moodId}/responses") {
                call.respondCreated(
                    ctx.moods.respond(call.user.userId, call.uuidParam("roomId"), call.uuidParam("moodId"), call.receive<CreateMoodReplyRequest>()),
                )
            }
            delete("/mood-responses/{id}") {
                call.respond(ctx.moods.withdraw(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }

            // ── 待办 ──
            post("/todos") {
                call.respondCreated(ctx.todos.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateTodoRequest>()))
            }
            patch("/todos/{id}") {
                call.respond(ctx.todos.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateTodoRequest>()))
            }
            delete("/todos/{id}") {
                call.respond(ctx.todos.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
            post("/todos/{id}/complete") {
                val body = call.receiveNullable<CompleteTodoRequest>() ?: CompleteTodoRequest()
                call.respond(ctx.todos.complete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), body))
            }
            post("/todos/{id}/reopen") {
                call.respond(ctx.todos.reopen(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }

            // ── 日程 ──
            post("/events") {
                call.respondCreated(ctx.events.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreateEventRequest>()))
            }
            patch("/events/{id}") {
                call.respond(ctx.events.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdateEventRequest>()))
            }
            delete("/events/{id}") {
                call.respond(ctx.events.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id")))
            }
        }
    }
}
