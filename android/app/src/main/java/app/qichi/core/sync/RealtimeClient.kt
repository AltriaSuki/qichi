package app.qichi.core.sync

import app.qichi.core.auth.TokenStore
import app.qichi.core.network.ApiClient
import app.qichi.core.network.get
import app.qichi.shared.api.API_PREFIX
import app.qichi.shared.api.Me
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.WsEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时通道：App 在前台时保持一条 WebSocket；开了「后台接收消息」时由 [app.qichi.core.push.BackgroundConnectionService] 在后台也保持着。
 * 收到 hello / changed 且服务端 seq 比本地新时拉取；断开后指数退避重连（1 秒到 1 分钟）。
 * 没有「正在输入」「在线」等事件。
 */
class RealtimeClient(
    private val api: ApiClient,
    private val tokenStore: TokenStore,
    private val syncEngine: SyncEngine,
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** AI 任务结束（成功或失败）：聊天里据此收起「正在想」或显示「没有得到回答」 */
    private val _aiDone = MutableSharedFlow<WsEvent.AiDone>(extraBufferCapacity = 8)
    val aiDone: SharedFlow<WsEvent.AiDone> = _aiDone.asSharedFlow()

    /** 内置通知：服务端发给我的通知（App 在后台时弹出来） */
    private val _notifications = MutableSharedFlow<WsEvent.Notify>(extraBufferCapacity = 16)
    val notifications: SharedFlow<WsEvent.Notify> = _notifications.asSharedFlow()

    /** 问 AI 边生成边显示：到目前为止的回答全文（P8-03）。 */
    private val _aiDeltas = MutableSharedFlow<WsEvent.AiDelta>(extraBufferCapacity = 32)
    val aiDeltas: SharedFlow<WsEvent.AiDelta> = _aiDeltas.asSharedFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            val token = tokenStore.read()?.accessToken ?: return
            try {
                api.http.webSocket(urlString = wsUrl(), request = { bearerAuth(token) }) {
                    _connected.value = true
                    backoffMs = 1_000L
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        // 不认识的事件（服务端比 App 新）跳过，不断开
                        val event = runCatching { QichiJson.decodeFromString(WsEvent.serializer(), frame.readText()) }.getOrNull() ?: continue
                        handle(event)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 可能是令牌过期：用一个普通请求触发自动刷新（离线时会失败，照样退避重连）
                runCatching { api.get<Me>("me") }
            } finally {
                _connected.value = false
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun handle(event: WsEvent) {
        try {
            when (event) {
                is WsEvent.Hello -> event.rooms.forEach { syncEngine.pullIfBehind(it.roomId, it.lastSeq) }
                is WsEvent.Changed -> syncEngine.pullIfBehind(event.roomId, event.seq)
                is WsEvent.AiDone -> {
                    _aiDone.tryEmit(event)
                    syncEngine.pull(event.roomId)
                }
                is WsEvent.Notify -> _notifications.tryEmit(event)
                is WsEvent.AiDelta -> _aiDeltas.tryEmit(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 拉取失败不影响连接；下次事件或定期同步会再拉
        }
    }

    private fun wsUrl(): String {
        // 告诉服务端这个 App 认识的事件：内置通知、问 AI 边生成边显示
        val http = baseUrl.trimEnd('/') + API_PREFIX + "/ws?caps=notify,ai_stream"
        return when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> http
        }
    }
}
