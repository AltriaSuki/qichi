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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
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
        install(WebSockets) { pingIntervalMillis = 30.seconds.inWholeMilliseconds }
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
        } catch (e: IOException) {
            throw NetworkException(e)
        } catch (e: Exception) {
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
