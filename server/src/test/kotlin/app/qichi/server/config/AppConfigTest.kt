package app.qichi.server.config

import app.qichi.shared.model.PushProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {

    private val productionVars = mapOf(
        "QICHI_ENV" to "production",
        "PUBLIC_BASE_URL" to "https://qichi.example.com/",
        "DATABASE_URL" to "jdbc:postgresql://db:5432/qichi",
        "DATABASE_USER" to "qichi",
        "DATABASE_PASSWORD" to "secret",
        "JWT_SECRET" to "x".repeat(48),
    )

    @Test
    fun `开发模式下未设置的变量使用本地开发数据库的默认值`() {
        val config = AppConfig.fromEnv(emptyMap())
        assertFalse(config.isProduction)
        assertEquals(8080, config.port)
        assertEquals("jdbc:postgresql://localhost:5432/qichi", config.database.url)
        assertEquals("qichi", config.database.user)
        assertEquals("qichi", config.database.password)
        assertEquals("http://localhost:8080", config.publicBaseUrl)
        assertTrue(config.jwtSecret.length >= AppConfig.JWT_SECRET_MIN_LENGTH)
        assertFalse(config.ai.isConfigured)
        assertTrue(config.pushProviders.isEmpty())
    }

    @Test
    fun `生产模式缺少关键变量时拒绝启动，并列出全部缺项`() {
        val e = assertFailsWith<ConfigException> { AppConfig.fromEnv(mapOf("QICHI_ENV" to "production")) }
        val text = e.problems.joinToString()
        for (name in listOf("JWT_SECRET", "DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "PUBLIC_BASE_URL")) {
            assertTrue(name in text, "应提示缺少 $name：$text")
        }
    }

    @Test
    fun `生产模式 JWT_SECRET 太短时拒绝启动`() {
        val e = assertFailsWith<ConfigException> {
            AppConfig.fromEnv(productionVars + ("JWT_SECRET" to "short"))
        }
        assertTrue(e.problems.single().contains("JWT_SECRET"))
    }

    @Test
    fun `生产模式配置完整时正常读取`() {
        val config = AppConfig.fromEnv(
            productionVars + mapOf("PORT" to "9000", "PUSH_PROVIDERS" to "unifiedpush", "UNIFIEDPUSH_ALLOWED_HOSTS" to " Push.Qichi1.duckdns.org "),
        )
        assertTrue(config.isProduction)
        assertEquals(9000, config.port)
        assertEquals("https://qichi.example.com", config.publicBaseUrl)
        assertEquals(setOf(PushProvider.UnifiedPush), config.pushProviders)
        assertEquals(setOf("push.qichi1.duckdns.org"), config.unifiedPushAllowedHosts)
    }

    @Test
    fun `空字符串等同于没设置`() {
        val config = AppConfig.fromEnv(mapOf("AI_PROVIDER" to "", "PUSH_PROVIDERS" to "  "))
        assertEquals(null, config.ai.provider)
        assertTrue(config.pushProviders.isEmpty())
    }

    @Test
    fun `不认识的取值会报错`() {
        assertFailsWith<ConfigException> { AppConfig.fromEnv(mapOf("PUSH_PROVIDERS" to "apns")) }
        // 两台手机没有谷歌服务，FCM 没有实现
        assertFailsWith<ConfigException> { AppConfig.fromEnv(mapOf("PUSH_PROVIDERS" to "fcm")) }
        assertFailsWith<ConfigException> { AppConfig.fromEnv(mapOf("PORT" to "abc")) }
        assertFailsWith<ConfigException> { AppConfig.fromEnv(mapOf("QICHI_ENV" to "staging")) }
    }

    @Test
    fun `打印配置时隐藏密码与密钥`() {
        val config = AppConfig.fromEnv(productionVars + ("AI_API_KEY" to "sk-secret"))
        assertFalse("secret" in config.database.toString())
        assertFalse("sk-secret" in config.ai.toString())
    }
}
