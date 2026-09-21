package app.qichi.server.plugins

import app.qichi.shared.api.CLIENT_HEADER
import app.qichi.shared.api.QichiJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.header
import org.slf4j.event.Level

fun Application.installSerialization() {
    install(ContentNegotiation) {
        json(QichiJson)
    }
}

/**
 * 请求日志：方法、路径（不含查询参数，因为搜索词等可能是私人内容）、状态码、耗时、客户端版本。
 * 不打印请求正文、令牌。
 */
fun Application.installCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        filter { call -> call.request.path() != "/api/v1/health" }
        format { call ->
            val status = call.response.status()?.value ?: "-"
            val client = call.request.header(CLIENT_HEADER) ?: "-"
            "${call.request.httpMethod.value} ${call.request.path()} $status ${call.processingTimeMillis()}ms client=$client"
        }
    }
}

fun Application.installDefaultHeaders() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
    }
}
