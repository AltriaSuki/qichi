package app.qichi.server.app

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.AppRelease
import app.qichi.shared.api.QichiJson
import app.qichi.shared.model.ProblemCode
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AppReleaseTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `还没发布时 404；发布后能取到版本信息和安装包；未登录 401`() {
        val ctx = testContext()
        serverTest(ctx) { client ->
            val (aqi, _, _) = Api(client).pair()
            aqi.get("/api/v1/app/latest").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

            val dir = ctx.config.filesDir.resolve("app-releases").also { Files.createDirectories(it) }
            val apk = "fake apk bytes".toByteArray()
            val release = AppRelease(12, "0.2.12", "修了推送", apk.size.toLong(), "abc", Instant.parse("2026-09-24T01:00:00Z"))
            Files.writeString(dir.resolve("latest.json"), QichiJson.encodeToString(AppRelease.serializer(), release))
            // 只有说明、没有安装包：当作没发布
            aqi.get("/api/v1/app/latest").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
            Files.write(dir.resolve("qichi-12.apk"), apk)

            assertEquals(release, aqi.get("/api/v1/app/latest").body<AppRelease>())
            val download = aqi.get("/api/v1/app/apk")
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals("application/vnd.android.package-archive", download.headers["Content-Type"])
            assertContentEquals(apk, download.bodyAsBytes())
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/app/latest").status)
        }
    }
}
