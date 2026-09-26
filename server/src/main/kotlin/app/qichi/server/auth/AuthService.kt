package app.qichi.server.auth

import app.qichi.server.db.Devices
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RefreshTokens
import app.qichi.server.db.Tx
import app.qichi.server.db.Users
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.RegisterRequest
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.rules.Limits
import app.qichi.shared.util.UuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import app.qichi.server.plugins.notFound
import java.util.UUID

/** 已登录的调用者：用户 + 这次登录（family）。 */
data class UserPrincipal(val userId: UUID, val familyId: UUID)

/**
 * 注册、登录、刷新、登出、改密码（docs/04-api.md「令牌」、docs/02-architecture.md §7）。
 */
class AuthService(
    private val db: QichiDatabase,
    private val hasher: PasswordHasher,
    private val tokens: TokenService,
    private val rooms: RoomService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 登录按用户名限流，另按 IP 限流（换着用户名猜也绕不过，P13-02）；改密码按用户限流；邀请码按 IP 限流。
     */
    val loginThrottle = FailureThrottle(clock)
    val loginIpThrottle = FailureThrottle(clock, maxFailures = LOGIN_IP_MAX_FAILURES)
    val passwordThrottle = FailureThrottle(clock)
    val inviteThrottle = FailureThrottle(clock, maxFailures = 10)

    /** 密码哈希（Argon2id，每次约 19MB 内存）：同时最多算这么多个，并且不占处理请求的线程。 */
    private val hashPermits = Semaphore(HASH_CONCURRENCY)

    private suspend fun <T> hashing(block: () -> T): T = hashPermits.withPermit { withContext(Dispatchers.Default) { block() } }

    data class Registered(val userId: UUID, val tokens: AuthTokens)

    suspend fun register(req: RegisterRequest, clientIp: String): Registered {
        val username = req.username.trim().lowercase()
        val displayName = req.displayName.trim()
        val inviteCode = req.inviteCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        validate {
            check(Limits.USERNAME_PATTERN.matches(username), "username", "3–32 位小写字母、数字或下划线")
            check(req.password.length in Limits.PASSWORD_LENGTH, "password", "密码 8–128 位")
            check(displayName.length in Limits.DISPLAY_NAME_LENGTH, "displayName", "显示名 1–32 个字")
            check((req.deviceName?.length ?: 0) <= Limits.DEVICE_NAME_MAX, "deviceName", "设备名太长")
        }
        if (inviteCode != null) inviteThrottle.check("invite:$clientIp")
        return try {
            // 先用便宜的查询挡掉注定失败的注册，再算密码哈希：Argon2 很贵，不能让任何人随便刷（P13-02）。
            // 这里不加锁，下面的事务里在锁内还会再判断一次。
            db.tx(readOnly = true) { checkCanRegister(username, inviteCode, precheckInvite = true) }
            val passwordHash = hashing { hasher.hash(req.password) }
            db.tx {
                // 串行化注册：保证「系统里还没有用户」的判断不会被两个并发请求同时通过
                jdbc.exec("SELECT pg_advisory_xact_lock($REGISTER_LOCK_KEY)")
                // 邀请码由下面的 redeem 在锁内完整校验
                checkCanRegister(username, inviteCode, precheckInvite = false)
                val now = clock.instant()
                val userId = UuidV7.generate()
                Users.insert {
                    it[id] = userId
                    it[Users.username] = username
                    it[Users.passwordHash] = passwordHash
                    it[Users.displayName] = displayName
                    it[notificationPrefs] = JsonObject(emptyMap())
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                if (inviteCode != null) rooms.redeem(this, inviteCode, userId)
                Registered(userId, newSession(userId, UuidV7.generate(), req.deviceName))
            }
        } catch (e: ApiException) {
            if (e.code == ProblemCode.InviteInvalid) inviteThrottle.recordFailure("invite:$clientIp")
            throw e
        }
    }

    /**
     * 注册资格：系统里已有用户时必须带邀请码；用户名不能重复。
     * [precheckInvite] 为 true 时顺带（不加锁地）看一眼邀请码能不能用，免得为注定失败的注册算哈希。
     */
    private fun checkCanRegister(username: String, inviteCode: String?, precheckInvite: Boolean) {
        if (inviteCode == null && Users.select(Users.id).limit(1).any()) {
            throw ApiException(ProblemCode.RegistrationClosed, "注册需要邀请码")
        }
        if (Users.select(Users.id).where { Users.username eq username }.any()) {
            throw ApiException(ProblemCode.UsernameTaken, "这个用户名已经有人用了")
        }
        if (inviteCode != null && precheckInvite) rooms.checkInvite(inviteCode)
    }

    suspend fun login(req: LoginRequest, clientIp: String): AuthTokens {
        val username = req.username.trim().lowercase()
        val key = "login:$username"
        val ipKey = "login-ip:$clientIp"
        loginThrottle.check(key)
        loginIpThrottle.check(ipKey)

        val user = db.tx {
            Users.select(Users.id, Users.passwordHash).where { Users.username eq username }.singleOrNull()
        }
        val ok = hashing {
            if (user == null) {
                hasher.dummyVerify(req.password)
                false
            } else {
                hasher.verify(req.password, user[Users.passwordHash])
            }
        }
        if (!ok) {
            loginThrottle.recordFailure(key)
            loginIpThrottle.recordFailure(ipKey)
            throw ApiException(ProblemCode.Unauthorized, "用户名或密码不正确")
        }
        loginThrottle.reset(key)
        val userId = user!![Users.id]
        return db.tx { newSession(userId, UuidV7.generate(), req.deviceName) }
    }

    /**
     * 刷新：换一对新令牌，旧的立即作废。已作废的刷新令牌被再次使用 → 这次登录的所有令牌全部作废。
     *
     * 例外（5 分钟宽限，P13-08）：弱网下常见「服务端已经换了新令牌、回应没送到手机」，手机只能拿旧令牌再试。
     * 被换掉的旧令牌 [REFRESH_GRACE] 内再出现、且换出来的新令牌还没被用过，就当作回应丢了：
     * 作废没送到的那对，再换发一对，并让旧令牌指向新发的这对（回应再丢一次也还能再试）。
     */
    suspend fun refresh(refreshToken: String): AuthTokens {
        val hash = TokenService.hashRefresh(refreshToken)
        val outcome = db.tx {
            val row = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }
                .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                ?: return@tx RefreshOutcome.Invalid
            val now = clock.instant()
            val familyId = row[RefreshTokens.familyId]
            when {
                row[RefreshTokens.revokedAt] != null -> {
                    val successor = row[RefreshTokens.replacedBy]?.let { id ->
                        RefreshTokens.selectAll().where { RefreshTokens.id eq id }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                    }
                    val lostResponse = successor != null &&
                        successor[RefreshTokens.revokedAt] == null &&
                        successor[RefreshTokens.expiresAt].isAfter(now) &&
                        !row[RefreshTokens.revokedAt]!!.plus(REFRESH_GRACE).isBefore(now)
                    if (lostResponse) {
                        val session = createSession(row[RefreshTokens.userId], familyId, row[RefreshTokens.deviceName])
                        RefreshTokens.update({ RefreshTokens.id eq successor!![RefreshTokens.id] }) {
                            it[revokedAt] = now
                            it[replacedBy] = session.refreshTokenId
                        }
                        RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) {
                            it[replacedBy] = session.refreshTokenId
                            it[lastUsedAt] = now
                        }
                        RefreshOutcome.Ok(session.tokens)
                    } else {
                        revokeFamily(familyId)
                        RefreshOutcome.Reused(row[RefreshTokens.userId], familyId)
                    }
                }
                !row[RefreshTokens.expiresAt].isAfter(now) -> RefreshOutcome.Invalid
                else -> {
                    val userId = row[RefreshTokens.userId]
                    val session = createSession(userId, familyId, row[RefreshTokens.deviceName])
                    RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) {
                        it[revokedAt] = now
                        it[replacedBy] = session.refreshTokenId
                        it[lastUsedAt] = now
                    }
                    RefreshOutcome.Ok(session.tokens)
                }
            }
        }
        return when (outcome) {
            is RefreshOutcome.Ok -> outcome.tokens
            is RefreshOutcome.Reused -> {
                log.warn("刷新令牌被重复使用，已作废该登录的全部令牌：user={} family={}", outcome.userId, outcome.familyId)
                throw ApiException(ProblemCode.Unauthorized, "登录已失效，请重新登录")
            }
            RefreshOutcome.Invalid -> throw ApiException(ProblemCode.Unauthorized, "登录已失效，请重新登录")
        }
    }

    private sealed interface RefreshOutcome {
        data class Ok(val tokens: AuthTokens) : RefreshOutcome
        data class Reused(val userId: UUID, val familyId: UUID) : RefreshOutcome
        data object Invalid : RefreshOutcome
    }

    /** 登出：作废这次登录的全部刷新令牌，注销这次登录注册的推送设备。 */
    suspend fun logout(principal: UserPrincipal) {
        db.tx {
            revokeFamily(principal.familyId)
        }
    }

    /** 改密码：作废其它所有登录；当前设备拿到一对新令牌。 */
    suspend fun changePassword(principal: UserPrincipal, req: ChangePasswordRequest): AuthTokens {
        validate {
            check(req.newPassword.length in Limits.PASSWORD_LENGTH, "newPassword", "密码 8–128 位")
        }
        val key = "password:${principal.userId}"
        passwordThrottle.check(key)
        val current = db.tx {
            Users.select(Users.passwordHash).where { Users.id eq principal.userId }.single()[Users.passwordHash]
        }
        if (!hashing { hasher.verify(req.currentPassword, current) }) {
            passwordThrottle.recordFailure(key)
            throw ApiException(ProblemCode.Unauthorized, "当前密码不正确")
        }
        passwordThrottle.reset(key)
        val newHash = hashing { hasher.hash(req.newPassword) }
        return db.tx {
            val now = clock.instant()
            Users.update({ Users.id eq principal.userId }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = now
                it[updatedAt] = now
            }
            val deviceName = RefreshTokens.select(RefreshTokens.deviceName)
                .where { RefreshTokens.familyId eq principal.familyId }
                .limit(1).singleOrNull()?.get(RefreshTokens.deviceName)
            RefreshTokens.update({ (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
            Devices.deleteWhere {
                (Devices.userId eq principal.userId) and (Devices.refreshFamilyId neq principal.familyId)
            }
            newSession(principal.userId, principal.familyId, deviceName)
        }
    }

    /** 「安全」页：我的有效登录（每次登录一行），最近用过的在前。 */
    suspend fun sessions(principal: UserPrincipal): List<LoginSession> = db.tx(readOnly = true) {
        val now = clock.instant()
        val rows = RefreshTokens.selectAll().where { RefreshTokens.userId eq principal.userId }.toList()
        rows.groupBy { it[RefreshTokens.familyId] }
            .filterValues { tokens -> tokens.any { it[RefreshTokens.revokedAt] == null && it[RefreshTokens.expiresAt] > now } }
            .map { (family, tokens) ->
                LoginSession(
                    id = family,
                    deviceName = tokens.firstNotNullOfOrNull { it[RefreshTokens.deviceName] },
                    createdAt = tokens.minOf { it[RefreshTokens.createdAt] },
                    lastUsedAt = tokens.maxOf { it[RefreshTokens.lastUsedAt] ?: it[RefreshTokens.createdAt] },
                    current = family == principal.familyId,
                )
            }
            .sortedByDescending { it.lastUsedAt }
    }

    /** 让某台设备退出登录（可以是自己这台，等于登出）；不是自己的登录一律 404。 */
    suspend fun revokeSession(principal: UserPrincipal, familyId: UUID) = db.tx {
        val mine = RefreshTokens.select(RefreshTokens.id).where {
            (RefreshTokens.familyId eq familyId) and (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull()
        }.limit(1).any()
        if (!mine) notFound()
        revokeFamily(familyId)
    }

    /** 访问令牌所属的登录是否仍然有效（登出、改密码、重复使用检测后立即失效）。 */
    suspend fun isSessionActive(userId: UUID, familyId: UUID): Boolean = db.tx {
        RefreshTokens.select(RefreshTokens.id).where {
            (RefreshTokens.familyId eq familyId) and (RefreshTokens.userId eq userId) and
                RefreshTokens.revokedAt.isNull() and (RefreshTokens.expiresAt greater clock.instant())
        }.limit(1).any()
    }

    private data class Session(val tokens: AuthTokens, val refreshTokenId: UUID)

    private fun Tx.newSession(userId: UUID, familyId: UUID, deviceName: String?): AuthTokens =
        createSession(userId, familyId, deviceName).tokens

    /** 在 [familyId] 这次登录下签发一对新令牌（刷新令牌入库，只存哈希）。 */
    private fun Tx.createSession(userId: UUID, familyId: UUID, deviceName: String?): Session {
        val now = clock.instant()
        val refresh = tokens.newRefresh()
        val refreshId = UuidV7.generate()
        RefreshTokens.insert {
            it[id] = refreshId
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.familyId] = familyId
            it[tokenHash] = refresh.hash
            it[RefreshTokens.deviceName] = deviceName?.trim()?.take(Limits.DEVICE_NAME_MAX)
            it[expiresAt] = refresh.expiresAt
            it[createdAt] = now
            it[lastUsedAt] = now
        }
        val access = tokens.issueAccess(userId, familyId)
        return Session(AuthTokens(access.token, access.expiresAt, refresh.token, refresh.expiresAt), refreshId)
    }

    private fun revokeFamily(familyId: UUID) {
        RefreshTokens.update({ (RefreshTokens.familyId eq familyId) and RefreshTokens.revokedAt.isNull() }) {
            it[revokedAt] = clock.instant()
        }
        Devices.deleteWhere { Devices.refreshFamilyId eq familyId }
    }

    private companion object {
        /** pg_advisory_xact_lock 的固定键：注册串行化 */
        const val REGISTER_LOCK_KEY = 7_140_001L

        /** 同一个 IP 15 分钟内最多输错这么多次（不管是哪个用户名） */
        const val LOGIN_IP_MAX_FAILURES = 20

        /** 同时计算的密码哈希个数上限（1GB 的服务器、256MB 的堆） */
        const val HASH_CONCURRENCY = 2

        /** 刷新回应丢失的宽限：被换掉的旧刷新令牌这么久之内再出现、新令牌还没被用过，就再换发一对（人类 2026-09-26 选定） */
        val REFRESH_GRACE: Duration = Duration.ofMinutes(5)
    }
}
