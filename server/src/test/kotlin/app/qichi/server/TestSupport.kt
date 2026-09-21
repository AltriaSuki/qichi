package app.qichi.server

import app.qichi.server.config.AppConfig
import app.qichi.server.config.DatabaseConfig
import app.qichi.server.db.QichiDatabase
import app.qichi.shared.api.QichiJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.time.Clock

/**
 * 所有测试共用一个 PostgreSQL 16 容器（Testcontainers），第一次用到时启动并执行迁移。
 * 每个测试开始前调用 [reset] 清空业务数据。
 */
object TestDatabase {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("qichi")
            .withUsername("qichi")
            .withPassword("qichi")
            .apply { start() }
    }

    val config: DatabaseConfig by lazy {
        DatabaseConfig(container.jdbcUrl, container.username, container.password, maxPoolSize = 10)
    }

    val database: QichiDatabase by lazy { QichiDatabase.start(config) }

    /** 清空除迁移记录外的所有表。 */
    fun reset() {
        database.dataSource.connection.use { conn ->
            val tables = conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            if (tables.isNotEmpty()) {
                conn.createStatement().use { it.execute("TRUNCATE ${tables.joinToString()} CASCADE") }
            }
            conn.commit()
        }
    }
}

fun testConfig(): AppConfig = AppConfig.fromEnv(emptyMap()).copy(
    database = TestDatabase.config,
    filesDir = Files.createTempDirectory("qichi-files-"),
)

fun testContext(config: AppConfig = testConfig(), clock: Clock = Clock.systemUTC()): AppContext =
    AppContext(config, TestDatabase.database, clock, BuildInfo.load())

/** 启动一个带完整模块的测试服务端；[extra] 可以挂测试专用的路由。 */
fun serverTest(
    ctx: AppContext = testContext(),
    extra: Application.() -> Unit = {},
    block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit,
) = testApplication {
    application {
        module(ctx)
        extra()
    }
    val client = createClient {
        install(ContentNegotiation) { json(QichiJson) }
    }
    block(client)
}
