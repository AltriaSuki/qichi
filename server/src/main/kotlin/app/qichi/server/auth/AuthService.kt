package app.qichi.server.auth

import app.qichi.server.db.Devices
import app.qichi.server.db.PasswordResetCodes
import app.qichi.server.db.QichiDatabase
import app.qichi.server.db.RefreshTokens
import app.qichi.server.db.Tx
import app.qichi.server.db.Users
import app.qichi.server.db.tx
import app.qichi.server.plugins.ApiException
import app.qichi.server.plugins.validate
import app.qichi.server.rooms.RoomRepository
import app.qichi.server.rooms.RoomService
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.ChangePasswordRequest
import app.qichi.shared.api.DeleteAccountRequest
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.PasswordResetCode
import app.qichi.shared.api.RegisterRequest
import app.qichi.shared.api.ResetPasswordRequest
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
import java.security.SecureRandom
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
    /** 这几次登录被作废之后（事务已提交）调用：断开它们的实时连接（P13-09） */
    private val onRevoked: suspend (userId: UUID, familyIds: Set<UUID>) -> Unit = { _, _ -> },
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val random = SecureRandom()

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
                onRevoked(outcome.userId, setOf(outcome.familyId))
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
        onRevoked(principal.userId, setOf(principal.familyId))
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
        var others: Set<UUID> = emptySet()
        val fresh = db.tx {
            val now = clock.instant()
            others = RefreshTokens.select(RefreshTokens.familyId)
                .where { (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull() }
                .map { it[RefreshTokens.familyId] }.toSet() - principal.familyId
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
        onRevoked(principal.userId, others)
        return fresh
    }

    // ── 忘了密码（P16-04）──

    /**
     * 房间里的一个人给另一个人生成重置码（对方忘了密码时）。只能给同一个房间里的另一个人；不是房间成员 404。
     * 再生成就换掉旧的；码只在这次回应里出现，库里只存哈希。
     */
    suspend fun createResetCode(callerId: UUID, roomId: UUID, targetId: UUID): PasswordResetCode = db.tx {
        rooms.requireMember(roomId, callerId)
        if (!RoomRepository.isMember(roomId, targetId)) notFound()
        validate { check(targetId != callerId, "userId", "自己的密码在「安全」里改") }
        issueResetCode(targetId, createdBy = callerId)
    }

    /** 两个人都忘了时，在服务器上用命令生成（`qichi-server reset-code 用户名`）。用户名不存在返回 null。 */
    suspend fun createResetCodeForUsername(username: String): PasswordResetCode? = db.tx {
        val userId = Users.select(Users.id).where { Users.username eq username.trim().lowercase() }.singleOrNull()?.get(Users.id)
            ?: return@tx null
        issueResetCode(userId, createdBy = null)
    }

    private fun Tx.issueResetCode(userId: UUID, createdBy: UUID?): PasswordResetCode {
        val now = clock.instant()
        val code = buildString { repeat(Limits.INVITE_LENGTH) { append(Limits.INVITE_ALPHABET[random.nextInt(Limits.INVITE_ALPHABET.length)]) } }
        val expiresAt = now.plus(RESET_CODE_VALID)
        PasswordResetCodes.deleteWhere { PasswordResetCodes.userId eq userId }
        PasswordResetCodes.insert {
            it[PasswordResetCodes.userId] = userId
            it[codeHash] = TokenService.hashRefresh(code)
            it[PasswordResetCodes.createdBy] = createdBy
            it[PasswordResetCodes.expiresAt] = expiresAt
            it[createdAt] = now
        }
        return PasswordResetCode(code, expiresAt)
    }

    /**
     * 用重置码设新密码：码对、没过期才行，用过就作废；所有登录（包括别的手机）都退出，这台直接登录。
     * 和登录一样按用户名、按 IP 限流，码不对和用户名不存在是同一句话。
     */
    suspend fun resetPassword(req: ResetPasswordRequest, clientIp: String): AuthTokens {
        validate {
            check(req.newPassword.length in Limits.PASSWORD_LENGTH, "newPassword", "密码 8–128 位")
        }
        val username = req.username.trim().lowercase()
        val key = "reset:$username"
        val ipKey = "login-ip:$clientIp"
        loginThrottle.check(key)
        loginIpThrottle.check(ipKey)
        val codeHash = TokenService.hashRefresh(req.code.trim().uppercase())
        val userId = db.tx {
            val user = Users.select(Users.id).where { Users.username eq username }.singleOrNull()?.get(Users.id) ?: return@tx null
            PasswordResetCodes.selectAll().where { PasswordResetCodes.userId eq user }.singleOrNull()
                ?.takeIf { it[PasswordResetCodes.codeHash] == codeHash && it[PasswordResetCodes.expiresAt].isAfter(clock.instant()) }
                ?.let { user }
        }
        if (userId == null) {
            loginThrottle.recordFailure(key)
            loginIpThrottle.recordFailure(ipKey)
            throw ApiException(ProblemCode.Unauthorized, "重置码不对或已经过期，请对方再生成一个")
        }
        loginThrottle.reset(key)
        val newHash = hashing { hasher.hash(req.newPassword) }
        var revoked: Set<UUID> = emptySet()
        val fresh = db.tx {
            val now = clock.instant()
            // 同一个码两台手机同时用：只有先删掉它的那个算数
            val used = PasswordResetCodes.deleteWhere { (PasswordResetCodes.userId eq userId) and (PasswordResetCodes.codeHash eq codeHash) }
            if (used == 0) throw ApiException(ProblemCode.Unauthorized, "重置码不对或已经过期，请对方再生成一个")
            revoked = RefreshTokens.select(RefreshTokens.familyId)
                .where { (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull() }
                .map { it[RefreshTokens.familyId] }.toSet()
            Users.update({ Users.id eq userId }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = now
                it[updatedAt] = now
            }
            RefreshTokens.update({ (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
            Devices.deleteWhere { Devices.userId eq userId }
            newSession(userId, UuidV7.generate(), req.deviceName)
        }
        log.info("用重置码设了新密码，其它 {} 次登录已退出", revoked.size)
        onRevoked(userId, revoked)
        return fresh
    }

    /**
     * 注销账号（P16-07）：要当前密码。写过的内容留在房间里，署名改成「已注销的成员」；只剩自己的房间整个删掉；
     * 用户名换成占位（原来的名字可以再注册）、密码作废、所有登录退出、推送设备和各种设置删掉。
     * @return 事务提交后要从磁盘删掉的文件（删掉的房间里的）
     */
    suspend fun deleteAccount(principal: UserPrincipal, req: DeleteAccountRequest): List<String> {
        val key = "password:${principal.userId}"
        passwordThrottle.check(key)
        val current = db.tx {
            Users.select(Users.passwordHash).where { Users.id eq principal.userId }.single()[Users.passwordHash]
        }
        if (!hashing { hasher.verify(req.password, current) }) {
            passwordThrottle.recordFailure(key)
            throw ApiException(ProblemCode.Unauthorized, "密码不正确")
        }
        passwordThrottle.reset(key)
        // 随便一串算出来的哈希：谁也不知道原文，等于作废
        val unusable = hashing { hasher.hash(UuidV7.generate().toString() + UuidV7.generate()) }
        var families: Set<UUID> = emptySet()
        val paths = db.tx {
            val now = clock.instant()
            families = RefreshTokens.select(RefreshTokens.familyId)
                .where { (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull() }
                .map { it[RefreshTokens.familyId] }.toSet()
            // 先改名字，房间里记的成员变化带出去的就是新名字
            Users.update({ Users.id eq principal.userId }) {
                it[username] = "deleted_" + UuidV7.generate().toString().replace("-", "").takeLast(20)
                it[displayName] = DELETED_NAME
                it[avatarFileId] = null
                it[passwordHash] = unusable
                it[passwordChangedAt] = now
                it[notificationPrefs] = JsonObject(emptyMap())
                it[aiPrefs] = JsonObject(emptyMap())
                it[readingPrompts] = emptyList()
                it[deletedAt] = now
                it[updatedAt] = now
            }
            val paths = rooms.removeUserEverywhere(this, principal.userId)
            RefreshTokens.update({ (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
            Devices.deleteWhere { Devices.userId eq principal.userId }
            PasswordResetCodes.deleteWhere { PasswordResetCodes.userId eq principal.userId }
            paths
        }
        log.info("注销了一个账号，{} 次登录已退出", families.size)
        onRevoked(principal.userId, families)
        return paths
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
    suspend fun revokeSession(principal: UserPrincipal, familyId: UUID) {
        db.tx {
            val mine = RefreshTokens.select(RefreshTokens.id).where {
                (RefreshTokens.familyId eq familyId) and (RefreshTokens.userId eq principal.userId) and RefreshTokens.revokedAt.isNull()
            }.limit(1).any()
            if (!mine) notFound()
            revokeFamily(familyId)
        }
        onRevoked(principal.userId, setOf(familyId))
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
        /** 注销后的显示名（P16-07） */
        const val DELETED_NAME = "已注销的成员"

        /** pg_advisory_xact_lock 的固定键：注册串行化 */
        const val REGISTER_LOCK_KEY = 7_140_001L

        /** 同一个 IP 15 分钟内最多输错这么多次（不管是哪个用户名） */
        const val LOGIN_IP_MAX_FAILURES = 20

        /** 同时计算的密码哈希个数上限（1GB 的服务器、256MB 的堆） */
        const val HASH_CONCURRENCY = 2

        /** 重置码的有效期 */
        val RESET_CODE_VALID: Duration = Duration.ofMinutes(15)

        /** 刷新回应丢失的宽限：被换掉的旧刷新令牌这么久之内再出现、新令牌还没被用过，就再换发一对（人类 2026-09-26 选定） */
        val REFRESH_GRACE: Duration = Duration.ofMinutes(5)
    }
}
