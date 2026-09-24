package app.qichi.core.network

import app.qichi.core.auth.TokenStore
import app.qichi.shared.api.API_PREFIX
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.CLIENT_HEADER
import app.qichi.shared.api.Problem
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * 访问服务端的唯一入口：
 * - 自动带上访问令牌与客户端版本头
 * - 收到 401 时自动刷新一次令牌再重试（多个请求同时 401 只刷新一次）；刷新也失败则清掉令牌、通知登出
 * - 错误统一转成 [ApiException]（带 problem+json）或 [NetworkException]
 */
class ApiClient(
    engine: HttpClientEngine,
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    clientVersion: String,
    /** 请求失败时记录日志（不含请求正文与令牌）。 */
    private val logFailure: (path: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val refreshMutex = Mutex()
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 刷新令牌失效时发出，SessionManager 据此回到登录页。 */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    val http: HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(QichiJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15.seconds.inWholeMilliseconds
            requestTimeoutMillis = 60.seconds.inWholeMilliseconds
        }
        // 心跳 60 秒：后台常驻连接（内置通知）时少唤醒手机；手机网络的连接一般 5 分钟以上才会被断
        install(WebSockets) { pingIntervalMillis = 60.seconds.inWholeMilliseconds }
        defaultRequest {
            url(baseUrl.trimEnd('/') + API_PREFIX + "/")
            header(CLIENT_HEADER, "android/$clientVersion")
        }
    }

    init {
        http.plugin(HttpSend).intercept { request ->
            if (request.attributes.contains(NoAuth)) return@intercept execute(request)
            val token = tokenStore.read()?.accessToken
            if (token != null) request.headers[HttpHeaders.Authorization] = "Bearer $token"
            val first = execute(request)
            if (first.response.status != HttpStatusCode.Unauthorized || token == null) return@intercept first

            val fresh = refreshTokens(usedAccessToken = token) ?: return@intercept first
            request.headers[HttpHeaders.Authorization] = "Bearer ${fresh.accessToken}"
            execute(request)
        }
    }

    /**
     * 刷新令牌（单飞）：若别的请求已经刷新过（令牌已变），直接用新的。
     * @return 新令牌；刷新失败返回 null（登录已失效时会清掉令牌并发出 [sessionExpired]）
     */
    private suspend fun refreshTokens(usedAccessToken: String): AuthTokens? = refreshMutex.withLock {
        val current = tokenStore.read() ?: return null
        if (current.accessToken != usedAccessToken) return current

        val response = try {
            http.request("auth/refresh") {
                method = HttpMethod.Post
                attributes.put(NoAuth, Unit)
                setBody(jsonBody(RefreshRequest(current.refreshToken)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        when {
            response.status.isSuccess() -> {
                val tokens = QichiJson.decodeFromString(AuthTokens.serializer(), response.bodyAsText())
                tokenStore.write(tokens)
                tokens
            }
            response.status == HttpStatusCode.Unauthorized -> {
                tokenStore.clear()
                _sessionExpired.tryEmit(Unit)
                null
            }
            else -> null
        }
    }

    /**
     * 发请求并解析响应体。2xx 返回 [T]（Unit 表示不需要响应体）；否则抛 [ApiException]。
     * @param auth false 时不带令牌、不自动刷新（注册、登录、刷新）
     */
    suspend fun <T> send(
        method: HttpMethod,
        path: String,
        responseSerializer: KSerializer<T>,
        body: Any? = null,
        auth: Boolean = true,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = execute(method, path, body, auth, configure)
        @Suppress("UNCHECKED_CAST")
        if (responseSerializer == Unit.serializer()) return Unit as T
        return QichiJson.decodeFromString(responseSerializer, response.bodyAsText())
    }

    /** 发请求，返回原始响应（非 2xx 抛 [ApiException]）。 */
    suspend fun execute(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        auth: Boolean = true,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = try {
            http.request(path.trimStart('/')) {
                this.method = method
                if (!auth) attributes.put(NoAuth, Unit)
                if (body != null) setBody(jsonBody(body))
                configure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            logFailure(path, e)
            throw NetworkException(e)
        }
        if (!response.status.isSuccess()) {
            if (auth && response.status == HttpStatusCode.Unauthorized && tokenStore.read() == null) {
                throw SessionExpiredException()
            }
            throw ApiException(response.status.value, parseProblem(response))
        }
        return response
    }

    /**
     * multipart 上传（文件）：不限总时长，[onProgress] 报告已发送 / 总字节数。
     * 非 2xx 抛 [ApiException]，网络问题抛 [NetworkException]。
     */
    suspend fun <T> upload(
        path: String,
        form: MultiPartFormDataContent,
        responseSerializer: KSerializer<T>,
        onProgress: (sent: Long, total: Long?) -> Unit = { _, _ -> },
    ): T {
        val response = execute(HttpMethod.Post, path) {
            setBody(form)
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            onUpload { sent, total -> onProgress(sent, total) }
        }
        return QichiJson.decodeFromString(responseSerializer, response.bodyAsText())
    }

    /** 下载文件到 [target]（先写临时文件，完整后改名），[onProgress] 报告已收到 / 总字节数。 */
    suspend fun download(path: String, target: File, onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> }) {
        val temp = File(target.parentFile, target.name + ".part")
        try {
            http.prepareGet(path.trimStart('/')) {
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                onDownload { received, total -> onProgress(received, total) }
            }.execute { response ->
                if (!response.status.isSuccess()) throw ApiException(response.status.value, parseProblem(response))
                withContext(Dispatchers.IO) {
                    temp.outputStream().use { out -> response.bodyAsChannel().toInputStream().copyTo(out) }
                }
            }
            if (!temp.renameTo(target)) throw IOException("无法保存文件")
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: ApiException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            logFailure(path, e)
            throw NetworkException(e)
        }
    }

    /**
     * 请求体统一由 QichiJson 编码成文本（不经 ContentNegotiation 按运行时类型猜序列化器）：
     * JsonElement 原样输出，数据类用它自己的序列化器（@EncodeDefault 等注解生效）。
     */
    private fun jsonBody(body: Any): TextContent {
        val text = when (body) {
            is JsonElement -> QichiJson.encodeToString(JsonElement.serializer(), body)
            is String -> body
            else -> QichiJson.encodeToString(QichiJson.serializersModule.serializer(body.javaClass), body)
        }
        return TextContent(text, ContentType.Application.Json)
    }

    private suspend fun parseProblem(response: HttpResponse): Problem? = runCatching {
        QichiJson.decodeFromString(Problem.serializer(), response.bodyAsText())
    }.getOrNull()

    companion object {
        /** 请求上带这个标记就不附加令牌、不自动刷新。 */
        val NoAuth = AttributeKey<Unit>("QichiNoAuth")
    }
}

suspend inline fun <reified T> ApiClient.get(path: String, auth: Boolean = true): T =
    send(HttpMethod.Get, path, serializer<T>(), auth = auth)

suspend inline fun <reified T> ApiClient.post(path: String, body: Any? = null, auth: Boolean = true): T =
    send(HttpMethod.Post, path, serializer<T>(), body, auth)

suspend inline fun <reified T> ApiClient.patch(path: String, body: Any): T =
    send(HttpMethod.Patch, path, serializer<T>(), body)

suspend inline fun <reified T> ApiClient.put(path: String, body: Any): T =
    send(HttpMethod.Put, path, serializer<T>(), body)

suspend inline fun <reified T> ApiClient.delete(path: String): T =
    send(HttpMethod.Delete, path, serializer<T>())
