package app.qichi.server.push

import app.qichi.server.Api
import app.qichi.server.MutableClock
import app.qichi.server.Session
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.server.testContext
import app.qichi.shared.api.CreateMoodRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.Device
import app.qichi.shared.api.Message
import app.qichi.shared.api.NotificationPrefs
import app.qichi.shared.api.Patch
import app.qichi.shared.api.PushPayload
import app.qichi.shared.api.RegisterDeviceRequest
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.UpdateMeRequest
import app.qichi.shared.model.MoodLabel
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.model.PushProvider
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 记下发了什么的假推送；[gone] 里的地址当作已经失效。 */
class FakePushSender : PushSender {
    val sent = CopyOnWriteArrayList<Pair<String, PushPayload>>()
    val gone = mutableSetOf<String>()
    override suspend fun send(endpoint: String, payload: String): SendResult {
        if (endpoint in gone) return SendResult.Gone
        sent += endpoint to Json.decodeFromString(PushPayload.serializer(), payload)
        return SendResult.Ok
    }
}

class PushTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    // 上海时间 12:00
    private val clock = MutableClock(Instant.parse("2026-09-23T04:00:00Z"))
    private val sender = FakePushSender()

    private suspend fun Session.device(endpoint: String) =
        post("/api/v1/devices", RegisterDeviceRequest(UuidV7.generate(), PushProvider.UnifiedPush, endpoint))

    /** 推送在后台协程里发：等一会儿。 */
    private suspend fun awaitSent(count: Int) {
        repeat(50) { if (sender.sent.size >= count) return; delay(40) }
    }

    @Test fun `对方发消息推给我，自己发的不推给自己；像 QQ 那样标题是谁、正文是内容，链接直达那条消息`() = serverTest(testContext(clock = clock, pushSender = sender)) { client ->
        val (aqi, chi, room) = Api(client).pair()
        assertEquals(HttpStatusCode.Created, aqi.device("https://push.example.com/aqi").status)
        chi.device("https://push.example.com/chi")
        val msg = chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "晚上一起吃面吧")).body<Message>()
        awaitSent(1)
        delay(200)
        assertEquals(1, sender.sent.size)
        val (endpoint, payload) = sender.sent.single()
        assertEquals("https://push.example.com/aqi", endpoint)
        assertEquals("xiaochi", payload.title)
        assertEquals("晚上一起吃面吧", payload.body)
        assertEquals(PushPayload.KIND_MESSAGE, payload.kind)
        assertEquals(msg.id, payload.messageId)
        assertEquals(chi.userId(), payload.senderId)
        assertEquals(false, payload.senderIsCreator)
        assertEquals("qichi://room/$room/chat/${msg.id}", payload.link)

        // 关掉「通知里显示内容」：只有谁做了什么，不带内容和发件人信息
        aqi.patch("/api/v1/me", UpdateMeRequest(notificationPrefs = Patch.of(NotificationPrefs(showPreview = false).toJson())))
        chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "明天见"))
        awaitSent(2)
        val plain = sender.sent.last().second
        assertEquals("xiaochi发来一条消息", plain.body)
        assertTrue("明天见" !in plain.body && plain.messageId == null && plain.sender == null)
    }

    @Test fun `心情需要安慰单独说；给我加的待办推给我；关掉的类别和免打扰时段不推`() = serverTest(testContext(clock = clock, pushSender = sender)) { client ->
        val (aqi, chi, room) = Api(client).pair()
        aqi.device("https://push.example.com/aqi")
        chi.post("/api/v1/rooms/$room/moods", CreateMoodRequest(UuidV7.generate(), MoodLabel.entries.first(), 3, null, needsComfort = true))
        awaitSent(1)
        assertEquals("需要一点安慰", sender.sent.last().second.body)
        assertEquals("xiaochi", sender.sent.last().second.title)
        chi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(UuidV7.generate(), "取快递", assigneeId = aqi.userId()))
        awaitSent(2)
        assertEquals("给你加了一件待办：取快递", sender.sent.last().second.body)

        // 关掉待办提醒
        aqi.patch("/api/v1/me", UpdateMeRequest(notificationPrefs = Patch.of(NotificationPrefs(todos = false).toJson())))
        chi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(UuidV7.generate(), "买菜", assigneeId = aqi.userId()))
        // 免打扰 11:00–13:00（现在 12:00）
        aqi.patch("/api/v1/me", UpdateMeRequest(notificationPrefs = Patch.of(NotificationPrefs(quietEnabled = true, quietStart = "11:00", quietEnd = "13:00").toJson())))
        chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "在吗"))
        delay(400)
        assertEquals(2, sender.sent.size)
    }

    @Test fun `推送地址失效时删掉设备；设备只能是 https 的 unifiedpush；只能注销自己的`() = serverTest(testContext(clock = clock, pushSender = sender)) { client ->
        val (aqi, chi, room) = Api(client).pair()
        val device = aqi.device("https://push.example.com/old").body<Device>()
        sender.gone += "https://push.example.com/old"
        chi.post("/api/v1/rooms/$room/messages", SendMessageRequest(UuidV7.generate(), "text", "hi"))
        delay(400)
        // 已经删掉：再注销是 404
        aqi.delete("/api/v1/devices/${device.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        aqi.device("http://evil.example.com/x").assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        aqi.post("/api/v1/devices", RegisterDeviceRequest(UuidV7.generate(), PushProvider.Fcm, "token")).assertProblem(HttpStatusCode.BadRequest, ProblemCode.InvalidRequest)
        val mine = aqi.device("https://push.example.com/new").body<Device>()
        // 同一个地址再注册：200，还是那台
        assertEquals(HttpStatusCode.OK, aqi.device("https://push.example.com/new").status)
        chi.delete("/api/v1/devices/${mine.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        assertEquals(HttpStatusCode.NoContent, aqi.delete("/api/v1/devices/${mine.id}").status)
    }

    @Test fun `只往允许的主机发`() {
        val s = UnifiedPushSender(setOf("push.qichi1.duckdns.org"))
        assertTrue(s.isAllowed("https://push.qichi1.duckdns.org/upAbc?up=1"))
        assertTrue(!s.isAllowed("https://other.example.com/x"))
        assertTrue(!s.isAllowed("http://push.qichi1.duckdns.org/x"))
        assertTrue(UnifiedPushSender(emptySet()).isAllowed("https://any.example.com/x"))
    }
}
