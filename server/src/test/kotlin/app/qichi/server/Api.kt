package app.qichi.server

import app.qichi.shared.api.AuthTokens
import app.qichi.shared.api.CreateRoomRequest
import app.qichi.shared.api.Invite
import app.qichi.shared.api.LoginRequest
import app.qichi.shared.api.Me
import app.qichi.shared.api.Problem
import app.qichi.shared.api.QichiJson
import app.qichi.shared.api.RegisterRequest
import app.qichi.shared.api.RoomDetail
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.assertEquals

/** 测试里调用接口的小工具。 */
class Api(val client: HttpClient) {

    suspend fun register(
        username: String,
        inviteCode: String? = null,
        password: String = "password-$username",
        displayName: String = username,
        deviceName: String? = null,
    ): HttpResponse = client.post("/api/v1/auth/register") {
        json(RegisterRequest(username, password, displayName, inviteCode, deviceName))
    }

    suspend fun registerOk(username: String, inviteCode: String? = null): Session {
        val response = register(username, inviteCode)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return Session(this, response.body())
    }

    suspend fun login(username: String, password: String = "password-$username"): HttpResponse =
        client.post("/api/v1/auth/login") { json(LoginRequest(username, password)) }

    suspend fun loginOk(username: String, password: String = "password-$username"): Session {
        val response = login(username, password)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Session(this, response.body())
    }

    /** 一个人建好房间、另一个人用邀请码加入：返回 (房主, 成员, 房间 id)。 */
    suspend fun pair(ownerName: String = "aqi", memberName: String = "xiaochi"): Triple<Session, Session, UUID> {
        val owner = registerOk(ownerName)
        val roomId = owner.createRoom("两个人的屋檐").room.id
        val invite = owner.post("/api/v1/rooms/$roomId/invites").body<Invite>()
        val member = registerOk(memberName, invite.code)
        return Triple(owner, member, roomId)
    }

    /** 不在 [owner] 房间里的人：[owner] 另建一个房间邀请进来。 */
    suspend fun outsider(owner: Session, name: String = "outsider"): Session {
        val other = owner.createRoom("另一个").room.id
        val invite = owner.post("/api/v1/rooms/$other/invites").body<Invite>()
        return registerOk(name, invite.code)
    }
}

/** 一个已登录的会话。 */
class Session(val api: Api, var tokens: AuthTokens) {
    private val client get() = api.client

    private fun HttpRequestBuilder.auth() = bearerAuth(tokens.accessToken)

    suspend fun get(path: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = client.get(path) { auth(); block() }
    suspend fun delete(path: String): HttpResponse = client.delete(path) { auth() }
    suspend fun post(path: String, body: Any? = null): HttpResponse = client.post(path) { auth(); if (body != null) json(body) }
    suspend fun patch(path: String, body: Any): HttpResponse = client.patch(path) { auth(); json(body) }
    suspend fun put(path: String, body: Any): HttpResponse = client.put(path) { auth(); json(body) }

    suspend fun userId(): UUID = get("/api/v1/me").body<Me>().user.id

    /** multipart 上传；[kindFirst] 为 false 时把 kind 字段放在文件后面。 */
    suspend fun upload(
        roomId: UUID,
        bytes: ByteArray,
        fileName: String = "photo.png",
        kind: String = "image",
        contentType: String = "image/png",
        id: UUID? = null,
        kindFirst: Boolean = true,
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/api/v1/rooms/$roomId/files",
        formData = formData {
            if (kindFirst) append("kind", kind)
            if (id != null) append("id", id.toString())
            append(
                "file",
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                },
            )
            if (!kindFirst) append("kind", kind)
        },
    ) { auth() }

    suspend fun createRoom(name: String, id: UUID = UuidV7.generate()): RoomDetail {
        val response = post("/api/v1/rooms", CreateRoomRequest(id = id, name = name))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }
}

fun HttpRequestBuilder.json(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

suspend fun HttpResponse.problem(): Problem = QichiJson.decodeFromString(Problem.serializer(), bodyAsText())

suspend fun HttpResponse.assertProblem(status: HttpStatusCode, code: ProblemCode): Problem {
    val text = bodyAsText()
    assertEquals(status, this.status, text)
    val problem = QichiJson.decodeFromString(Problem.serializer(), text)
    assertEquals(code, problem.code, text)
    return problem
}
