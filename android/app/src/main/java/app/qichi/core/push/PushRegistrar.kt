package app.qichi.core.push

import android.content.Context
import app.qichi.core.auth.LogoutHook
import app.qichi.core.network.ApiClient
import app.qichi.core.network.post
import app.qichi.shared.api.Device
import app.qichi.shared.api.PushPayload
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RegisterDeviceRequest
import app.qichi.shared.model.PushProvider
import app.qichi.shared.util.UuidV7
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 这台手机的推送登记：设备 id（一台手机固定一个）和 ntfy 给的推送地址。 */
interface PushStore {
    var deviceId: String?
    var endpoint: String?

    /** 内置通知（App 自己在后台保持连接），默认开 */
    var builtIn: Boolean
}

class SharedPrefsPushStore(context: Context) : PushStore {
    private val prefs = context.getSharedPreferences("qichi-push", Context.MODE_PRIVATE)
    override var deviceId: String?
        get() = prefs.getString("deviceId", null)
        set(value) = prefs.edit().putString("deviceId", value).apply()
    override var endpoint: String?
        get() = prefs.getString("endpoint", null)
        set(value) = prefs.edit().putString("endpoint", value).apply()
    override var builtIn: Boolean
        get() = prefs.getBoolean("builtIn", true)
        set(value) = prefs.edit().putBoolean("builtIn", value).apply()
}

/**
 * 推送（P3-10，UnifiedPush）：把 ntfy 给的推送地址登记到服务端，登出时注销。
 * 和系统打交道的部分（选分发器、注册）在 [QichiPushService] 与通知页里，这里只管状态和接口。
 */
class PushRegistrar(private val api: ApiClient, private val store: PushStore) : LogoutHook {
    private val _endpoint = MutableStateFlow(store.endpoint)
    private val _builtIn = MutableStateFlow(store.builtIn)

    /** 内置通知开没开 */
    val builtIn: StateFlow<Boolean> = _builtIn.asStateFlow()

    fun setBuiltIn(on: Boolean) {
        store.builtIn = on
        _builtIn.value = on
    }

    /** 非空 = 推送已开启（拿到了推送地址）。 */
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    private fun deviceId(): UUID =
        store.deviceId?.let(UUID::fromString) ?: UuidV7.generate().also { store.deviceId = it.toString() }

    /** ntfy 给了新的推送地址。登录着就马上登记（失败的话下次打开 App 再登记）。 */
    suspend fun onNewEndpoint(url: String, loggedIn: Boolean) {
        store.endpoint = url
        _endpoint.value = url
        if (loggedIn) upload()
    }

    /** 把推送地址登记到服务端；登录后、每次启动时调用（服务端按地址去重，重复登记没关系）。 */
    suspend fun upload(): Boolean {
        val url = store.endpoint ?: return false
        return quietly { api.post<Device>("devices", RegisterDeviceRequest(deviceId(), PushProvider.UnifiedPush, url)) }
    }

    /** 关掉推送或 ntfy 那边注销了：本机忘掉地址，服务端也删掉。 */
    suspend fun onUnregistered(loggedIn: Boolean) {
        store.endpoint = null
        _endpoint.value = null
        if (loggedIn) unregisterRemote()
    }

    /** 登出前注销，免得登出后还收到这个账号的推送。本机的推送地址留着，下次登录重新登记。 */
    override suspend fun beforeLogout() {
        if (store.endpoint != null) unregisterRemote()
    }

    private suspend fun unregisterRemote() {
        val id = store.deviceId ?: return
        quietly { api.execute(HttpMethod.Delete, "devices/$id") }
    }

    private suspend fun quietly(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        /**
         * 解析收到的推送；不认识的内容返回 null（不弹通知）。
         * 深链只接受 `qichi://room/...`，别的一律丢掉，免得被人借推送打开奇怪的地址。
         */
        fun parse(bytes: ByteArray): PushPayload? {
            val payload = runCatching { QichiJson.decodeFromString(PushPayload.serializer(), bytes.decodeToString()) }.getOrNull()
                ?: return null
            return sanitize(payload)
        }

        /** ntfy 来的和实时通道来的都过一遍：只认 qichi 深链，长度截断。 */
        fun sanitize(payload: PushPayload): PushPayload? {
            if (!payload.link.startsWith("qichi://room/")) return null
            return payload.copy(
                title = payload.title.take(40), body = payload.body.take(PushPayload.PREVIEW_MAX), tag = payload.tag.take(80),
                sender = payload.sender?.take(40), roomName = payload.roomName?.take(40),
            )
        }
    }
}
