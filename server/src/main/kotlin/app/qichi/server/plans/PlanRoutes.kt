package app.qichi.server.plans

import app.qichi.server.AppContext
import app.qichi.server.life.respondCreated
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CompletePlanRequest
import app.qichi.shared.api.CreateMilestoneRequest
import app.qichi.shared.api.CreatePlanLogRequest
import app.qichi.shared.api.CreatePlanRequest
import app.qichi.shared.api.CreatePlanStageRequest
import app.qichi.shared.api.UpdateMilestoneRequest
import app.qichi.shared.api.UpdatePlanRequest
import app.qichi.shared.api.UpdatePlanStageRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.planRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/plans") {
            get { call.respond(ctx.plans.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respondCreated(ctx.plans.create(call.user.userId, call.uuidParam("roomId"), call.receive<CreatePlanRequest>())) }
            get("/{id}") { call.respond(ctx.plans.detail(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            patch("/{id}") { call.respond(ctx.plans.update(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"), call.receive<UpdatePlanRequest>())) }
            delete("/{id}") { call.respond(ctx.plans.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
            post("/{planId}/complete") {
                call.respond(ctx.plans.complete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.receive<CompletePlanRequest>()))
            }
            post("/{planId}/stages") {
                call.respondCreated(ctx.plans.createStage(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.receive<CreatePlanStageRequest>()))
            }
            patch("/{planId}/stages/{id}") {
                call.respond(ctx.plans.updateStage(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.uuidParam("id"), call.receive<UpdatePlanStageRequest>()))
            }
            delete("/{planId}/stages/{id}") {
                call.respond(ctx.plans.deleteStage(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.uuidParam("id")))
            }
            post("/{planId}/milestones") {
                call.respondCreated(ctx.plans.createMilestone(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.receive<CreateMilestoneRequest>()))
            }
            patch("/{planId}/milestones/{id}") {
                call.respond(ctx.plans.updateMilestone(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.uuidParam("id"), call.receive<UpdateMilestoneRequest>()))
            }
            delete("/{planId}/milestones/{id}") {
                call.respond(ctx.plans.deleteMilestone(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.uuidParam("id")))
            }
            post("/{planId}/logs") {
                call.respondCreated(ctx.plans.createLog(call.user.userId, call.uuidParam("roomId"), call.uuidParam("planId"), call.receive<CreatePlanLogRequest>()))
            }
        }
    }
}
