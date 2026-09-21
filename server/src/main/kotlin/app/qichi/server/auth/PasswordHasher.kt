package app.qichi.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id 密码哈希，输出标准 PHC 字符串：`$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`。
 * 默认参数取 OWASP 推荐的最低配置（19 MiB 内存、2 轮、1 并行）；测试里可以调低以加快速度。
 */
class PasswordHasher(
    private val memoryKib: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
) {
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val out = derive(password, salt, memoryKib, iterations, parallelism, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism\$${b64.encodeToString(salt)}\$${b64.encodeToString(out)}"
    }

    /** 校验密码；哈希串格式不对时返回 false。比较用恒定时间。 */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=…,t=…,p=…", salt, hash]
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val m = params["m"] ?: return false
        val t = params["t"] ?: return false
        val p = params["p"] ?: return false
        val salt = runCatching { b64d.decode(parts[4]) }.getOrNull() ?: return false
        val expected = runCatching { b64d.decode(parts[5]) }.getOrNull() ?: return false
        val actual = derive(password, salt, m, t, p, expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    /** 用户不存在时也做一次同样耗时的计算，避免通过响应时间判断用户名是否存在。 */
    fun dummyVerify(password: String) {
        verify(password, DUMMY_HASH)
    }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), out)
        return out
    }

    private val DUMMY_HASH: String by lazy { hash("dummy-password-for-timing") }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}
