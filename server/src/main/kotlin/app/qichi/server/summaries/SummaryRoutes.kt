package app.qichi.server.summaries

import app.qichi.server.AppContext
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.Summaries
import app.qichi.server.db.EntityWrites
import app.qichi.server.db.tx
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.forbidden
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.CreateSummaryRequest
import app.qichi.shared.api.Summary
import app.qichi.shared.model.EntityType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** 总结（P6-06）：列表与删除；生成走 AiService（AI 请求的统一模式）。年度回顾锁定，不能删。 */
class SummaryService(
    private val db: QichiDatabase,
    private val rooms: RoomService,
    private val writes: EntityWrites,
) {
    private fun summary(id: UUID) = Summaries.selectAll().where { Summaries.id eq id }.singleOrNull()?.toSummary()

    suspend fun list(userId: UUID, roomId: UUID): List<Summary> = db.tx(readOnly = true) {
        rooms.requireMember(roomId, userId)
        Summaries.selectAll().where { (Summaries.roomId eq roomId) and Summaries.deletedAt.isNull() }
            .orderBy(Summaries.createdAt, SortOrder.DESC).map { it.toSummary() }
    }

    suspend fun delete(userId: UUID, roomId: UUID, id: UUID): Summary = db.tx {
        rooms.requireMember(roomId, userId)
        val current = summary(id)?.takeIf { it.roomId == roomId } ?: notFound()
        if (current.locked) forbidden("年度回顾不能删除")
        if (current.deletedAt == null) writes.softDelete(this, roomId, userId, EntityType.Summary, id, Summaries)
        summary(id)!!
    }
}

fun Route.summaryRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/summaries") {
            get { call.respond(ctx.summaries.list(call.user.userId, call.uuidParam("roomId"))) }
            post { call.respond(HttpStatusCode.Accepted, ctx.ai.createSummary(call.user.userId, call.uuidParam("roomId"), call.receive<CreateSummaryRequest>())) }
            delete("/{id}") { call.respond(ctx.summaries.delete(call.user.userId, call.uuidParam("roomId"), call.uuidParam("id"))) }
        }
    }
}
