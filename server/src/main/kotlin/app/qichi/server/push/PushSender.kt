package app.qichi.server.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

enum class SendResult {
    Ok,

    /** 推送地址已经失效（手机卸载了、换了分发器），应当删掉这个设备 */
    Gone,

    /** 暂时没发出去（网络、服务器错误），这次就算了 */
    Failed,
}

/** 把一条推送发到某个设备的推送地址。 */
fun interface PushSender {
    suspend fun send(endpoint: String, payload: String): SendResult
}

private val log = LoggerFactory.getLogger(UnifiedPushSender::class.java)

/**
 * UnifiedPush：设备注册时上报的 token 就是分发器（自建 ntfy）给的推送地址，POST 过去即可。
 * 只往 https 地址发（本机开发可以用 localhost）；配置了 [allowedHosts] 时只往这些主机发，防止被当成跳板请求任意地址。
 */
class UnifiedPushSender(
    private val allowedHosts: Set<String>,
    engine: HttpClientEngine = CIO.create(),
) : PushSender {
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

    fun isAllowed(endpoint: String): Boolean {
        val url = runCatching { Url(endpoint) }.getOrNull() ?: return false
        val host = url.host.lowercase()
        val local = host == "localhost" || host == "127.0.0.1"
        if (url.protocol.name != "https" && !local) return false
        return allowedHosts.isEmpty() || host in allowedHosts
    }

    override suspend fun send(endpoint: String, payload: String): SendResult {
        if (!isAllowed(endpoint)) return SendResult.Gone
        return try {
            val response = http.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when {
                response.status.value in 200..299 -> SendResult.Ok
                response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone -> SendResult.Gone
                else -> SendResult.Failed.also { log.warn("推送没发出去：HTTP {}", response.status.value) }
            }
        } catch (e: Exception) {
            log.warn("推送没发出去：{}", e.javaClass.simpleName)
            SendResult.Failed
        }
    }
}
