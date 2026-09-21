package app.qichi.server.plugins

import app.qichi.server.auth.AuthService
import app.qichi.server.auth.TokenService
import app.qichi.server.auth.UserPrincipal
import app.qichi.shared.model.ProblemCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import java.util.UUID

const val AUTH_JWT = "jwt"

/**
 * JWT 鉴权：校验签名、过期时间，并确认这次登录（family）没有被作废——
 * 登出、改密码、刷新令牌被重复使用之后，旧的访问令牌立即失效。
 */
fun Application.installSecurity(tokens: TokenService, auth: AuthService) {
    // 线上只能经 Caddy 访问，客户端 IP 取 X-Forwarded-For
    install(XForwardedHeaders)
    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier(tokens.verifier)
            validate { credential ->
                val userId = credential.subject?.toUuidOrNull()
                val familyId = credential.payload.getClaim(TokenService.FAMILY_CLAIM).asString()?.toUuidOrNull()
                if (userId != null && familyId != null && auth.isSessionActive(userId, familyId)) {
                    UserPrincipal(userId, familyId)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respondProblem(ApiException(ProblemCode.Unauthorized, "请先登录"))
            }
        }
    }
}

val ApplicationCall.user: UserPrincipal
    get() = principal<UserPrincipal>() ?: throw ApiException(ProblemCode.Unauthorized, "请先登录")

val ApplicationCall.clientIp: String
    get() = request.origin.remoteHost

fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

/** 路径参数里的 UUID；格式不对时当作不存在（404）。 */
fun ApplicationCall.uuidParam(name: String): UUID =
    parameters[name]?.toUuidOrNull() ?: notFound()
