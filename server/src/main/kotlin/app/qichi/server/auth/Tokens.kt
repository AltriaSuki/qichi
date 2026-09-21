package app.qichi.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * 令牌：
 * - 访问令牌：JWT（HS256），15 分钟；sub = 用户 id，fam = 这次登录的 family id。
 * - 刷新令牌：32 字节随机串（base64url），60 天；数据库只存 SHA-256。
 */
class TokenService(
    secret: String,
    private val clock: Clock,
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(60),
) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val random = SecureRandom()

    /** 校验签名、签发方、受众和过期时间（按注入的时钟判断过期）。 */
    val verifier: JWTVerifier = (
        JWT.require(algorithm)
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaimPresence(FAMILY_CLAIM) as JWTVerifier.BaseVerification
        ).build(clock)

    data class AccessToken(val token: String, val expiresAt: Instant)
    data class RefreshToken(val token: String, val hash: String, val expiresAt: Instant)

    fun issueAccess(userId: UUID, familyId: UUID): AccessToken {
        val now = clock.instant()
        val expiresAt = now.plus(accessTtl)
        val token = JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId.toString())
            .withClaim(FAMILY_CLAIM, familyId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return AccessToken(token, expiresAt)
    }

    fun newRefresh(): RefreshToken {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return RefreshToken(token, hashRefresh(token), clock.instant().plus(refreshTtl))
    }

    companion object {
        const val ISSUER = "qichi"
        const val AUDIENCE = "qichi-app"
        const val FAMILY_CLAIM = "fam"

        fun hashRefresh(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
