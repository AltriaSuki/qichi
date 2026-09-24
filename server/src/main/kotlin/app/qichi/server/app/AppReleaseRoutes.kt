package app.qichi.server.app

import app.qichi.server.AppContext
import app.qichi.server.plugins.AUTH_JWT
import app.qichi.server.plugins.notFound
import app.qichi.shared.api.AppRelease
import app.qichi.shared.api.QichiJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * App 内置更新：发布时把安装包和说明放进 `FILES_DIR/app-releases/`（`latest.json` + `qichi-<versionCode>.apk`，
 * 见 tools/publish_release.sh），服务端只负责告诉 App 最新是哪一版、把安装包给它。
 */
class AppReleases(filesDir: Path) {
    private val dir = filesDir.resolve("app-releases")

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val json = dir.resolve("latest.json")
        if (!json.exists()) return@withContext null
        runCatching { QichiJson.decodeFromString(AppRelease.serializer(), json.readText()) }.getOrNull()
            ?.takeIf { apk(it).exists() }
    }

    fun apk(release: AppRelease): Path = dir.resolve("qichi-${release.versionCode}.apk")
}

fun Route.appReleaseRoutes(ctx: AppContext) {
    authenticate(AUTH_JWT) {
        get("/app/latest") {
            call.respond(ctx.appReleases.latest() ?: notFound())
        }
        get("/app/apk") {
            val release = ctx.appReleases.latest() ?: notFound()
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "qichi-${release.versionName}.apk").toString(),
            )
            call.respond(LocalPathContent(ctx.appReleases.apk(release), ContentType("application", "vnd.android.package-archive")))
        }
    }
}
