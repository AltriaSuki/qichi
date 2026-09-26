package app.qichi.server.config

import app.qichi.shared.model.PushProvider
import app.qichi.shared.model.fromWireOrNull
import java.nio.file.Path

/**
 * 服务端配置，全部来自环境变量（变量名与 deploy/.env.example、deploy/docker-compose.yml 一致）。
 *
 * - 开发模式（默认）：没设置的变量用 deploy/docker-compose.dev.yml 对应的本地默认值。
 * - 生产模式（QICHI_ENV=production）：缺少关键变量时拒绝启动，并列出缺了哪些。
 */
data class AppConfig(
    val env: Env,
    val port: Int,
    val publicBaseUrl: String,
    val database: DatabaseConfig,
    val jwtSecret: String,
    val filesDir: Path,
    val ai: AiConfig,
    val pushProviders: Set<PushProvider>,
    /** UnifiedPush 只往这些主机发（例如自建的 push.qichi1.duckdns.org）；为空时任何 https 地址都可以 */
    val unifiedPushAllowedHosts: Set<String> = emptySet(),
    /** 审稿文档转换服务（Gotenberg，内含 LibreOffice）；没配时只能预览 PDF */
    val converterUrl: String? = null,
) {
    enum class Env { Development, Production }

    val isProduction: Boolean get() = env == Env.Production

    companion object {
        const val JWT_SECRET_MIN_LENGTH = 32
        private const val DEV_JWT_SECRET = "dev-only-insecure-jwt-secret-do-not-use-in-production"

        fun fromEnv(vars: Map<String, String> = System.getenv()): AppConfig {
            fun get(name: String): String? = vars[name]?.trim()?.takeIf { it.isNotEmpty() }

            val env = when (get("QICHI_ENV")?.lowercase()) {
                null, "development", "dev" -> Env.Development
                "production", "prod" -> Env.Production
                else -> throw ConfigException(listOf("QICHI_ENV 只能是 development 或 production"))
            }
            val production = env == Env.Production
            val problems = mutableListOf<String>()

            fun required(name: String, devDefault: String): String =
                get(name) ?: if (production) {
                    problems += "缺少 $name"
                    ""
                } else {
                    devDefault
                }

            val port = get("PORT")?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                    problems += "PORT 不是合法端口：$raw"
                    8080
                }
            } ?: 8080

            val jwtSecret = required("JWT_SECRET", DEV_JWT_SECRET)
            if (production && jwtSecret.isNotEmpty() && jwtSecret.length < JWT_SECRET_MIN_LENGTH) {
                problems += "JWT_SECRET 至少 $JWT_SECRET_MIN_LENGTH 个字符"
            }

            val database = DatabaseConfig(
                url = required("DATABASE_URL", "jdbc:postgresql://localhost:5432/qichi"),
                user = required("DATABASE_USER", "qichi"),
                password = required("DATABASE_PASSWORD", "qichi"),
                maxPoolSize = get("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10,
            )
            val publicBaseUrl = required("PUBLIC_BASE_URL", "http://localhost:$port").trimEnd('/')

            val pushProviders = get("PUSH_PROVIDERS").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { name ->
                    fromWireOrNull<PushProvider>(name) ?: run {
                        problems += "PUSH_PROVIDERS 里有不认识的值：$name（可选 fcm、unifiedpush）"
                        null
                    }
                }.toSet()
            // 两台手机没有谷歌服务（2026-09-23 决定），只实现了 UnifiedPush
            if (PushProvider.Fcm in pushProviders) problems += "PUSH_PROVIDERS：fcm 还没有实现，请用 unifiedpush"

            val ai = AiConfig(
                provider = get("AI_PROVIDER"),
                baseUrl = get("AI_BASE_URL"),
                apiKey = get("AI_API_KEY"),
                model = get("AI_MODEL"),
                monthlyTokenLimit = get("AI_MONTHLY_TOKEN_LIMIT")?.toLongOrNull() ?: 2_000_000,
                tools = get("AI_TOOLS")?.lowercase() != "off",
            )

            if (problems.isNotEmpty()) throw ConfigException(problems)

            return AppConfig(
                env = env,
                port = port,
                publicBaseUrl = publicBaseUrl,
                database = database,
                jwtSecret = jwtSecret,
                filesDir = Path.of(get("FILES_DIR") ?: "./data/files").toAbsolutePath().normalize(),
                ai = ai,
                pushProviders = pushProviders,
                unifiedPushAllowedHosts = get("UNIFIEDPUSH_ALLOWED_HOSTS").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                converterUrl = get("CONVERTER_URL")?.trim()?.trimEnd('/')?.ifEmpty { null },
            )
        }
    }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=***, maxPoolSize=$maxPoolSize)"
}

data class AiConfig(
    val provider: String?,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    val monthlyTokenLimit: Long,
    /** 问 AI 时让模型自己查资料（P11）；服务商不支持工具调用时设 AI_TOOLS=off，退回只用事先备料 */
    val tools: Boolean = true,
) {
    /** 四项都填了才算配置了 AI；否则 AI 接口返回 ai_unavailable。 */
    val isConfigured: Boolean get() = provider != null && baseUrl != null && apiKey != null && model != null

    override fun toString(): String =
        "AiConfig(provider=$provider, baseUrl=$baseUrl, apiKey=${if (apiKey == null) "null" else "***"}, model=$model, monthlyTokenLimit=$monthlyTokenLimit, tools=$tools)"
}

class ConfigException(val problems: List<String>) :
    RuntimeException("配置不完整，服务端拒绝启动：\n" + problems.joinToString("\n") { "  - $it" })
