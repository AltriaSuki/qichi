package app.qichi.core.auth

import java.util.UUID

/**
 * 本机数据属于哪个账号（P13-08）。登录被动失效时本机数据留着：
 * 同一个账号重新登录就接着用、接着发；换成别的账号时先提示再清。主动登出时一起清掉。
 */
interface LocalOwnerStore {
    suspend fun read(): UUID?
    suspend fun write(userId: UUID?)
}

/** 内存里的（测试用）。 */
class InMemoryLocalOwnerStore(private var owner: UUID? = null) : LocalOwnerStore {
    override suspend fun read(): UUID? = owner
    override suspend fun write(userId: UUID?) {
        owner = userId
    }
}
