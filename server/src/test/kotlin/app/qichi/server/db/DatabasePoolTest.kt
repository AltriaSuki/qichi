package app.qichi.server.db

import app.qichi.server.TestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabasePoolTest {
    /**
     * 连接池新建连接时执行的初始化语句（设时区）不能留下没结束的事务：
     * 否则每条新连接第一次用时换隔离级别就会报「事务进行中不能改隔离级别」（启动后头几个请求 500）。
     */
    @Test fun `新连接第一次用就能换隔离级别`() = runBlocking {
        QichiDatabase.start(TestDatabase.config).use { db ->
            val results = (1..8).map {
                async {
                    db.tx(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                        TransactionManager.current().exec("SELECT current_setting('TimeZone')") { rs -> rs.next(); rs.getString(1) }
                    }
                }
            }.awaitAll()
            assertEquals(List(8) { "UTC" }, results)
        }
    }
}
