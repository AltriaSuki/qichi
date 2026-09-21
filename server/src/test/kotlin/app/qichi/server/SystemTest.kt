package app.qichi.server

import app.qichi.shared.api.Health
import io.ktor.client.call.body
import io.ktor.client.request.get
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
}
