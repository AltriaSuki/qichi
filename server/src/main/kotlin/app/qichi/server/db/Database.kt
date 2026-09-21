package app.qichi.server.db

import app.qichi.server.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** 数据库连接池 + Flyway 迁移 + Exposed。服务端启动时调用 [start]。 */
class QichiDatabase private constructor(
    val dataSource: HikariDataSource,
    val exposed: Database,
) : AutoCloseable {

    override fun close() = dataSource.close()

    companion object {
        private val log = LoggerFactory.getLogger(QichiDatabase::class.java)

        fun start(config: DatabaseConfig): QichiDatabase {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password
                    maximumPoolSize = config.maxPoolSize
                    poolName = "qichi"
                    isAutoCommit = false
                    transactionIsolation = "TRANSACTION_READ_COMMITTED"
                    connectionInitSql = "SET TIME ZONE 'UTC'"
                },
            )
            try {
                migrate(dataSource)
            } catch (e: Exception) {
                dataSource.close()
                throw e
            }
            return QichiDatabase(dataSource, Database.connect(dataSource))
        }

        /** 执行 resources/db/migration 下尚未执行的迁移。 */
        fun migrate(dataSource: DataSource) {
            val result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            log.info(
                "数据库迁移完成：执行了 {} 个迁移，当前版本 {}",
                result.migrationsExecuted,
                result.targetSchemaVersion ?: result.initialSchemaVersion ?: "空",
            )
        }
    }
}
