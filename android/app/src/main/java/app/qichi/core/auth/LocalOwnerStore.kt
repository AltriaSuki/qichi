package app.qichi.core.auth

import java.time.Instant
import java.util.UUID

/** 上一次登录为什么、什么时候被动结束（P21-07）：登录页说清楚原因，下次再出现「莫名其妙要重新登录」时好查。 */
data class SessionEnd(val reason: SessionEndReason, val at: Instant)

enum class SessionEndReason {
    /** 服务端不认这台手机的刷新令牌了（在别的手机上退出了这台、改了密码，或者旧的刷新令牌又被用了一次） */
    Rejected,

    /** 这台手机上存着令牌，却解不开（系统的密钥出了问题） */
    Unreadable,
}

/**
 * 本机数据属于哪个账号（P13-08）。登录被动失效时本机数据留着：
 * 同一个账号重新登录就接着用、接着发；换成别的账号时先提示再清。主动登出时一起清掉。
 * 另外记着上一次登录被动结束的原因（[SessionEnd]），重新登录、主动登出后清掉。
 */
interface LocalOwnerStore {
    suspend fun read(): UUID?
    suspend fun write(userId: UUID?)
    suspend fun readEnd(): SessionEnd?
    suspend fun writeEnd(end: SessionEnd?)
}

/** 内存里的（测试用）。 */
class InMemoryLocalOwnerStore(private var owner: UUID? = null) : LocalOwnerStore {
    private var end: SessionEnd? = null
    override suspend fun read(): UUID? = owner
    override suspend fun write(userId: UUID?) {
        owner = userId
    }
    override suspend fun readEnd(): SessionEnd? = end
    override suspend fun writeEnd(end: SessionEnd?) {
        this.end = end
    }
}
