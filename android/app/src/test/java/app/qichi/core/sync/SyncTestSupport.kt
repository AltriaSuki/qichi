package app.qichi.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.qichi.core.auth.InMemoryTokenStore
import app.qichi.core.database.QichiDatabase
import app.qichi.core.network.ApiClient
import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.Message
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.SendMessageRequest
import app.qichi.shared.api.Todo
import app.qichi.shared.model.MessageKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** 同步测试的公共夹具：内存数据库 + 可控的假服务端。 */
object SyncFixtures {
    val roomId: UUID = UUID.fromString("0192f000-0000-7000-8000-00000000000a")
    val me: UUID = UUID.fromString("0192f000-0000-7000-8000-000000000001")
    val partner: UUID = UUID.fromString("0192f000-0000-7000-8000-000000000002")
    val t0: Instant = Instant.parse("2026-09-21T10:00:00Z")

    fun database(): QichiDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), QichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun tokens() = AuthTokens("h.eyJzdWIiOiIwMTkyZjAwMC0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwMDEifQ.s", t0, "r", t0)

    fun api(engine: MockEngine) = ApiClient(engine, "http://test", InMemoryTokenStore(tokens()), "test")

    fun pendingMessage(body: String, id: UUID = UUID.randomUUID()) = Message(
        id = id, roomId = roomId, seq = 0, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
        authorId = me, kind = MessageKind.Text, body = body, file = null, replyToId = null, replyAuthorId = null,
        replyExcerpt = null, retractedAt = null, retractedBy = null, createdSeq = 0,
    )

    fun todo(title: String, id: UUID = UUID.randomUUID(), seq: Long = 0) = Todo(
        id = id, roomId = roomId, seq = seq, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
        title = title, note = null, createdBy = me, assigneeId = null, parentId = null, dueDate = null, dueAt = null,
        recurrence = null, recurrencePrevId = null, doneAt = null, doneBy = null,
    )
}

/**
 * 假服务端：消息接口按 id 幂等（同 id 再发返回已存在的那条），可以模拟离线、5xx、响应丢失。
 * 其它路径交给 [custom] 处理。
 */
class FakeServer {
    var online = true

    /** 下一次请求在服务端处理成功后「丢掉响应」（模拟网络在响应回来前断开） */
    var loseNextResponse = false

    /** 接下来 N 次请求返回 500 */
    var failNext500 = 0

    var seq = 0L
    val messages = linkedMapOf<UUID, Message>()
    val requests = mutableListOf<String>()
    var custom: (MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    val engine = MockEngine { request ->
        if (!online) throw IOException("offline")
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (failNext500 > 0) {
            failNext500--
            return@MockEngine respond(
                """{"type":"x","title":"服务器出错了","status":500,"code":"internal_error"}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        custom?.invoke(this, request)?.let { return@MockEngine it }

        val response = if (request.method.value == "POST" && path.endsWith("/messages")) {
            val req = QichiJson.decodeFromString(SendMessageRequest.serializer(), request.bodyText())
            val existing = messages[req.id]
            val message = existing ?: run {
                seq++
                SyncFixtures.pendingMessage(req.body!!, req.id).copy(seq = seq, createdSeq = seq)
                    .also { messages[req.id] = it }
            }
            respond(
                QichiJson.encodeToString(Message.serializer(), message),
                if (existing == null) HttpStatusCode.Created else HttpStatusCode.OK,
                json,
            )
        } else {
            respond("""{"type":"x","title":"找不到","status":404,"code":"not_found"}""", HttpStatusCode.NotFound)
        }
        if (loseNextResponse) {
            loseNextResponse = false
            throw IOException("connection reset after server processed the request")
        }
        response
    }
}

fun HttpRequestData.bodyText(): String = when (val b = body) {
    is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
    else -> ""
}
