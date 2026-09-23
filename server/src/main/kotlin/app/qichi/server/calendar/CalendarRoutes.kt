package app.qichi.server.calendar

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.shared.api.CalendarSubscriptionRequest
import app.qichi.shared.model.ProblemCode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream

private const val ICS_MAX_BYTES = 2 * 1024 * 1024
private val ICS_TYPE = ContentType.parse("text/calendar; charset=utf-8")

fun Route.calendarRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/rooms/{roomId}/calendar") {
            post("/import") {
                val roomId = call.uuidParam("roomId")
                val userId = call.user.userId
                ctx.rooms.requireMemberTx(roomId, userId)
                if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    throw ApiException(ProblemCode.UnsupportedMediaType, "需要 multipart/form-data")
                }
                var bytes: ByteArray? = null
                call.receiveMultipart(formFieldLimit = ICS_MAX_BYTES.toLong() + 64 * 1024).forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && part.name == "file" && bytes == null) {
                            bytes = part.provider().toInputStream().use { it.readNBytes(ICS_MAX_BYTES + 1) }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val data = bytes ?: throw ApiException(ProblemCode.InvalidRequest, "没有 ICS 文件")
                if (data.size > ICS_MAX_BYTES) throw ApiException(ProblemCode.PayloadTooLarge, "ICS 文件不能超过 2 MiB")
                call.respond(ctx.calendar.import(userId, roomId, data))
            }
            get("/export.ics") {
                val data = ctx.calendar.export(call.user.userId, call.uuidParam("roomId"))
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"qichi-calendar.ics\"")
                call.respondBytes(data, ICS_TYPE)
            }
            post("/subscription") {
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.respond(ctx.calendar.subscribe(call.user.userId, call.uuidParam("roomId"),
                    call.receive<CalendarSubscriptionRequest>().reset))
            }
        }
    }
    get("/ics/{file}") {
        val file = call.parameters["file"] ?: notFound()
        val token = file.removeSuffix(".ics").takeIf { file.endsWith(".ics") && it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: notFound()
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respondBytes(ctx.calendar.feed(token), ICS_TYPE)
    }
}
