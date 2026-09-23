package app.qichi.server.calendar

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.Bootstrap
import app.qichi.shared.api.CalendarImportResult
import app.qichi.shared.api.CalendarSubscription
import app.qichi.shared.api.CalendarSubscriptionRequest
import app.qichi.shared.api.CompleteTodoRequest
import app.qichi.shared.api.CreateTodoRequest
import app.qichi.shared.api.SyncResponse
import app.qichi.shared.api.Todo
import app.qichi.shared.model.EntityType
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarRoutesTest {
    @BeforeEach fun reset() = TestDatabase.reset()

    private val file = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Qichi Test//ZH
        BEGIN:VEVENT
        UID:trip@example.test
        DTSTAMP:20260921T000000Z
        DTSTART;VALUE=DATE:20260921
        DTEND;VALUE=DATE:20260923
        SUMMARY:海边旅行
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n").toByteArray()

    @Test fun `导入按 UID 去重，同步与导出均能看到`() = serverTest { client ->
        val (aqi, chi, room) = Api(client).pair()
        val path = "/api/v1/rooms/$room/calendar"
        suspend fun upload() = client.submitFormWithBinaryData("$path/import", formData {
            append("file", file, Headers.build {
                append(HttpHeaders.ContentType, "text/calendar")
                append(HttpHeaders.ContentDisposition, "filename=\"trip.ics\"")
            })
        }) { bearerAuth(aqi.tokens.accessToken) }
        assertEquals(CalendarImportResult(1, 0), upload().body())
        assertEquals(CalendarImportResult(0, 1), upload().body())
        val event = chi.get("/api/v1/rooms/$room/bootstrap").body<Bootstrap>().events.single()
        assertEquals("trip@example.test", event.icsUid)
        assertEquals("2026-09-22", event.endDate.toString())
        assertTrue(chi.get("/api/v1/rooms/$room/sync?since=0").body<SyncResponse>().changes
            .any { it.type == EntityType.Event && it.id == event.id })
        val exported = chi.get("$path/export.ics").bodyAsText()
        assertTrue(exported.contains("UID:trip@example.test"))
        assertEquals(listOf("trip@example.test"), IcsCodec.parse(exported.toByteArray(), java.time.ZoneId.of("Asia/Shanghai")).first.map { it.uid })
    }

    @Test fun `导出带上待办截止，已完成的待办不再出现`() = serverTest { client ->
        val (aqi, _, room) = Api(client).pair()
        val day = java.time.LocalDate.of(2026, 9, 25)
        val open = aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(UuidV7.generate(), "订餐位", dueDate = day)).body<Todo>()
        val done = aqi.post("/api/v1/rooms/$room/todos", CreateTodoRequest(UuidV7.generate(), "取外套", dueDate = day)).body<Todo>()
        assertEquals(HttpStatusCode.OK, aqi.post("/api/v1/rooms/$room/todos/${done.id}/complete", CompleteTodoRequest()).status)
        val exported = aqi.get("/api/v1/rooms/$room/calendar/export.ics").bodyAsText()
        assertTrue(exported.contains("todo-${open.id}@qichi"))
        assertFalse(exported.contains("todo-${done.id}@qichi"))
    }

    @Test fun `订阅链接可重置，旧链接立刻失效，非成员不能管理`() = serverTest { client ->
        val api = Api(client)
        val (aqi, chi, room) = api.pair()
        val outsider = api.outsider(aqi)
        val path = "/api/v1/rooms/$room/calendar"
        outsider.get("$path/export.ics").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        outsider.post("$path/subscription", CalendarSubscriptionRequest()).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        val first = aqi.post("$path/subscription", CalendarSubscriptionRequest()).body<CalendarSubscription>()
        val again = chi.post("$path/subscription", CalendarSubscriptionRequest()).body<CalendarSubscription>()
        assertEquals(first, again)
        val oldPath = first.url.substringAfter("/api/v1/").let { "/api/v1/$it" }
        assertEquals(HttpStatusCode.OK, client.get(oldPath).status)
        val second = chi.post("$path/subscription", CalendarSubscriptionRequest(reset = true)).body<CalendarSubscription>()
        assertFalse(first.url == second.url)
        client.get(oldPath).assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        val newPath = second.url.substringAfter("/api/v1/").let { "/api/v1/$it" }
        assertEquals(HttpStatusCode.OK, client.get(newPath).status)
    }
}
