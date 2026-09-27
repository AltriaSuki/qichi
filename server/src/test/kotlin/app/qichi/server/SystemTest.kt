package app.qichi.server

import app.qichi.server.db.QichiDatabase
import app.qichi.shared.api.Health
import app.qichi.shared.model.ProblemCode
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemTest {

    @BeforeTest
    fun setUp() = TestDatabase.reset()

    @Test
    fun `health 返回 ok 和版本号`() = serverTest { client ->
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.Json))
        val health = response.body<Health>()
        assertEquals("ok", health.status)
        assertEquals("0.1.0", health.version)
    }

    @Test
    fun `数据库连不上时健康检查报错，部署脚本和监控能发现（Q12）`() {
        val db = QichiDatabase.start(TestDatabase.config)
        db.close()
        serverTest(AppContext(testConfig(), db, MutableClock(), BuildInfo.load(), fastHasher)) { client ->
            client.get("/api/v1/health").assertProblem(HttpStatusCode.InternalServerError, ProblemCode.InternalError)
        }
    }

    @Test
    fun `启动时执行了 V1 迁移，第 1–3 阶段的表都在`() {
        TestDatabase.database.dataSource.connection.use { conn ->
            val applied = conn.createStatement().executeQuery(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getBoolean(2)) } }
            assertEquals(listOf("1" to true), applied.take(1))

            val tables = conn.createStatement().executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
            ).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            val expected = setOf(
                "users", "refresh_tokens", "rooms", "room_members", "invites", "change_log", "files",
                "messages", "read_markers", "moods", "mood_responses", "todos", "events", "devices",
            )
            assertTrue(tables.containsAll(expected), "缺少的表：${expected - tables}")
        }
    }

    @Test
    fun `JSON 回复按请求压缩：说能收 gzip 就压，不说就不压`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        repeat(20) { aqi.post("/api/v1/rooms/$room/messages", app.qichi.shared.api.SendMessageRequest(app.qichi.shared.util.UuidV7.generate(), "text", "压缩测试第 $it 条，长一点才会压缩。")) }
        val gz = aqi.get("/api/v1/rooms/$room/bootstrap") { header(io.ktor.http.HttpHeaders.AcceptEncoding, "gzip") }
        kotlin.test.assertEquals("gzip", gz.headers[io.ktor.http.HttpHeaders.ContentEncoding])
        val plain = aqi.get("/api/v1/rooms/$room/bootstrap")
        kotlin.test.assertEquals(null, plain.headers[io.ktor.http.HttpHeaders.ContentEncoding])
    }
}
