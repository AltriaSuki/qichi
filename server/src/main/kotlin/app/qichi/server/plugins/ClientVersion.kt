package app.qichi.server.plugins

import app.qichi.shared.api.API_PREFIX
import app.qichi.shared.api.CLIENT_HEADER
import app.qichi.shared.api.androidVersionCodeOf
import app.qichi.shared.model.ProblemCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.path

/**
 * 太旧的 App 直接拦下（P13-07）：`X-Qichi-Client` 里的 versionCode 小于 [minAndroidVersionCode]（MIN_ANDROID_VERSION_CODE）时，
 * 返回 426 `upgrade_required`，App 提示更新。0 表示不限制（默认）。
 *
 * 不拦：健康检查、注册登录刷新（`/auth/…`）、App 更新（`/app/…`）——旧 App 要能刷新令牌、下载新版；
 * 没带版本头或读不出版本号的请求（例如日历订阅、调试用的 curl）也不拦。
 */
fun Application.installClientVersionCheck(minAndroidVersionCode: Int) {
    if (minAndroidVersionCode <= 0) return
    install(
        createApplicationPlugin("ClientVersionCheck") {
            onCall { call ->
                val path = call.request.path()
                if (!path.startsWith("$API_PREFIX/") || EXEMPT.any { path == it || path.startsWith("$it/") }) return@onCall
                val code = androidVersionCodeOf(call.request.header(CLIENT_HEADER)) ?: return@onCall
                if (code < minAndroidVersionCode) {
                    throw ApiException(ProblemCode.UpgradeRequired, "App 版本太旧，更新后才能继续使用", detail = "至少需要版本 $minAndroidVersionCode，现在是 $code")
                }
            }
        },
    )
}

private val EXEMPT = listOf("$API_PREFIX/health", "$API_PREFIX/auth", "$API_PREFIX/app")
