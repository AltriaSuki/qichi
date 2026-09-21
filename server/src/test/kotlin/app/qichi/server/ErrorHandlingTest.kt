package app.qichi.server

import app.qichi.server.plugins.ApiException
import app.qichi.shared.api.PROBLEM_CONTENT_TYPE
import app.qichi.shared.api.Problem
import app.qichi.shared.api.QichiJson
import app.qichi.shared.model.ProblemCode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorHandlingTest {

    @Serializable
    data class Echo(val name: String, val count: Int)

    private val testRoutes: io.ktor.server.application.Application.() -> Unit = {
        routing {
            get("/test/room-full") {
                throw ApiException(ProblemCode.RoomFull, "房间已经有两个人了")
            }
            get("/test/rate-limited") {
                throw ApiException(ProblemCode.RateLimited, "太频繁了", retryAfterSeconds = 30)
            }
            get("/test/boom") {
                error("数据库密码是 hunter2")
            }
            post("/test/echo") {
                call.respond(call.receive<Echo>())
            }
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.problem(): Problem {
        assertTrue(contentType()!!.match(ContentType.parse(PROBLEM_CONTENT_TYPE)), "Content-Type 应为 problem+json")
        return QichiJson.decodeFromString(Problem.serializer(), bodyAsText())
    }

    @Test
    fun `不存在的路径返回 404 not_found`() = serverTest(extra = testRoutes) { client ->
        val response = client.get("/api/v1/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem = response.problem()
        assertEquals(ProblemCode.NotFound, problem.code)
        assertEquals(404, problem.status)
        assertEquals("https://qichi.app/errors/not_found", problem.type)
    }

    @Test
    fun `业务错误转成 problem+json，状态码按 code 决定`() = serverTest(extra = testRoutes) { client ->
        val response = client.get("/test/room-full")
        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = response.problem()
        assertEquals(ProblemCode.RoomFull, problem.code)
        assertEquals("房间已经有两个人了", problem.title)
    }

    @Test
    fun `限流带 Retry-After`() = serverTest(extra = testRoutes) { client ->
        val response = client.get("/test/rate-limited")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("30", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `请求体不是合法 JSON 或缺字段时返回 400 invalid_request`() = serverTest(extra = testRoutes) { client ->
        for (body in listOf("{not json", """{"name":"a"}""", """{"name":"a","count":"x"}""")) {
            val response = client.post("/test/echo") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals(ProblemCode.InvalidRequest, response.problem().code)
        }
    }

    @Test
    fun `未处理的异常返回 500，且不泄露内部信息`() = serverTest(extra = testRoutes) { client ->
        val response = client.get("/test/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val text = response.bodyAsText()
        assertFalse("hunter2" in text)
        assertEquals(ProblemCode.InternalError, QichiJson.decodeFromString(Problem.serializer(), text).code)
    }
}
