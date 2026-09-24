package app.qichi.server.plugins

import app.qichi.shared.api.CLIENT_HEADER
import app.qichi.shared.api.QichiJson
import app.qichi.shared.model.ProblemCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.header
import org.slf4j.event.Level

fun Application.installSerialization() {
    // 文本（JSON 等）压缩：同步数据能小 6–9 倍。图片、安装包、压缩包本来就压缩过，不再压
    install(Compression) {
        gzip {
            minimumSize(1024)
            matchContentType(ContentType.Application.Json, ContentType.Application.ProblemJson, ContentType.Text.Any, ContentType("text", "calendar"))
        }
    }
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
            "${call.request.httpMethod.value} ${call.safePath()} $status ${call.processingTimeMillis()}ms client=$client"
        }
    }
}

/** 订阅路径里的随机令牌是凭证，任何请求与错误日志都必须遮掉。 */
fun ApplicationCall.safePath(): String = request.path().let { path ->
    if (path.startsWith("/api/v1/ics/")) "/api/v1/ics/{token}.ics" else path
}

fun Application.installDefaultHeaders() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
    }
}

/** 可选的整数查询参数；写了但不是整数时 400。 */
fun ApplicationCall.longQuery(name: String): Long? = request.queryParameters[name]?.let {
    it.toLongOrNull() ?: throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "$name 必须是整数")
}

fun ApplicationCall.intQuery(name: String): Int? = longQuery(name)?.let {
    if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw ApiException(ProblemCode.InvalidRequest, "请求参数不合法", detail = "$name 超出范围")
}
