package app.qichi.core.push

import app.qichi.core.sync.SyncFixtures
import app.qichi.core.sync.bodyText
import app.qichi.shared.api.Device
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RegisterDeviceRequest
import app.qichi.shared.model.PushProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRegistrarTest {
    private class MemoryStore : PushStore {
        override var deviceId: String? = null
        override var endpoint: String? = null
        override var builtIn: Boolean = true
    }

    private val requests = mutableListOf<HttpRequestData>()
    private var offline = false
    private val engine = MockEngine { request ->
        requests += request
        when {
            offline -> respondError(HttpStatusCode.ServiceUnavailable)
            request.method == HttpMethod.Post -> {
                val body = QichiJson.decodeFromString(RegisterDeviceRequest.serializer(), request.bodyText())
                respond(
                    QichiJson.encodeToString(Device.serializer(), Device(body.id, body.provider, body.token, Instant.EPOCH)),
                    HttpStatusCode.Created,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }
    private val store = MemoryStore()
    private val registrar = PushRegistrar(SyncFixtures.api(engine), store)
    private val url = "https://push.qichi1.duckdns.org/upAbC123?up=1"

    @Test
    fun `拿到推送地址：登录着就登记，设备 id 固定不变`() = runTest {
        registrar.onNewEndpoint(url, loggedIn = true)
        assertEquals(url, registrar.endpoint.value)
        val first = QichiJson.decodeFromString(RegisterDeviceRequest.serializer(), requests.single().bodyText())
        assertEquals(PushProvider.UnifiedPush, first.provider)
        assertEquals(url, first.token)

        assertTrue(registrar.upload())
        val second = QichiJson.decodeFromString(RegisterDeviceRequest.serializer(), requests.last().bodyText())
        assertEquals(first.id, second.id)
    }

    @Test
    fun `没登录时只记下地址，登录后再登记；离线登记失败不报错`() = runTest {
        registrar.onNewEndpoint(url, loggedIn = false)
        assertTrue(requests.isEmpty())
        assertEquals(url, store.endpoint)

        offline = true
        assertFalse(registrar.upload())
        offline = false
        assertTrue(registrar.upload())
    }

    @Test
    fun `没开推送时登录不登记，登出也不注销`() = runTest {
        assertFalse(registrar.upload())
        registrar.beforeLogout()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `登出前注销设备，但本机地址留着；关闭推送连地址一起忘掉`() = runTest {
        registrar.onNewEndpoint(url, loggedIn = true)
        val id = store.deviceId
        registrar.beforeLogout()
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertTrue(requests.last().url.encodedPath.endsWith("/devices/$id"), requests.last().url.encodedPath)
        assertEquals(url, store.endpoint)

        registrar.onUnregistered(loggedIn = true)
        assertNull(registrar.endpoint.value)
        assertNull(store.endpoint)
        assertEquals(HttpMethod.Delete, requests.last().method)
    }

    @Test
    fun `解析推送：只接受 qichi 深链，坏内容不弹通知`() {
        val ok = PushRegistrar.parse("""{"title":"栖迟","body":"小迟发来一条消息","link":"qichi://room/r1/chat","tag":"r1:chat","extra":1}""".toByteArray())
        assertNotNull(ok)
        assertEquals("小迟发来一条消息", ok.body)
        assertNull(PushRegistrar.parse("""{"title":"x","body":"y","link":"https://evil.example","tag":"t"}""".toByteArray()))
        assertNull(PushRegistrar.parse("不是 JSON".toByteArray()))
        assertNull(PushRegistrar.parse(ByteArray(0)))
    }
}
