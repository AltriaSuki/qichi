package app.qichi.server.devices

import app.qichi.server.AppContext
import app.qichi.server.auth.UserPrincipal
import app.qichi.server.db.Devices
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.tx
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.notFound
import app.qichi.server.plugins.user
import app.qichi.server.plugins.uuidParam
import app.qichi.server.plugins.validate
import app.qichi.shared.api.Device
import app.qichi.shared.api.RegisterDeviceRequest
import app.qichi.shared.model.PushProvider
import app.qichi.shared.model.fromWire
import app.qichi.shared.model.wireName
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.util.UUID

private fun ResultRow.toDevice() = Device(
    id = this[Devices.id], provider = fromWire(this[Devices.provider]), token = this[Devices.token],
    createdAt = this[Devices.createdAt],
)

/**
 * 推送设备（P3-10）：App 拿到推送地址后注册。同一个 provider + token 已存在时改到当前用户、当前登录名下（换了账号的手机）。
 * 登出、改密码时随登录一起注销（AuthService）。
 */
class DeviceService(private val db: QichiDatabase, private val clock: Clock) {
    suspend fun register(principal: UserPrincipal, req: RegisterDeviceRequest): Pair<Device, Boolean> {
        validate {
            check(req.token.length in 1..2048, "token", "推送地址不对")
            check(req.provider == PushProvider.UnifiedPush, "provider", "只支持 unifiedpush")
            check(req.token.startsWith("https://") || req.token.startsWith("http://localhost") || req.token.startsWith("http://127.0.0.1"), "token", "推送地址要是 https")
        }
        return db.tx {
            val now = clock.instant()
            val existing = Devices.selectAll().where { (Devices.provider eq req.provider.wireName) and (Devices.token eq req.token) }.singleOrNull()
            if (existing != null) {
                Devices.update({ Devices.id eq existing[Devices.id] }) {
                    it[userId] = principal.userId
                    it[refreshFamilyId] = principal.familyId
                    it[updatedAt] = now
                }
                Devices.selectAll().where { Devices.id eq existing[Devices.id] }.single().toDevice() to false
            } else {
                Devices.insert {
                    it[id] = req.id
                    it[userId] = principal.userId
                    it[provider] = req.provider.wireName
                    it[token] = req.token
                    it[refreshFamilyId] = principal.familyId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                Devices.selectAll().where { Devices.id eq req.id }.single().toDevice() to true
            }
        }
    }

    suspend fun unregister(principal: UserPrincipal, id: UUID) = db.tx {
        val deleted = Devices.deleteWhere { (Devices.id eq id) and (Devices.userId eq principal.userId) }
        if (deleted == 0) notFound()
    }
}

fun Route.deviceRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        route("/devices") {
            post {
                val (device, created) = ctx.devices.register(call.user, call.receive<RegisterDeviceRequest>())
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, device)
            }
            delete("/{id}") {
                ctx.devices.unregister(call.user, call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
