package app.qichi.server.me

import app.qichi.server.Api
import app.qichi.server.TestDatabase
import app.qichi.server.assertProblem
import app.qichi.server.serverTest
import app.qichi.shared.api.LoginSession
import app.qichi.shared.api.Me
import app.qichi.shared.model.ProblemCode
import app.qichi.shared.util.UuidV7
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTest {
    @BeforeTest fun reset() = TestDatabase.reset()

    @Test fun `登录设备：每次登录一行，标出当前这台；让另一台退出后它就不能用了；别人的登录 404`() = serverTest { client ->
        val api = Api(client)
        val (phone, other, _) = api.pair()
        val tablet = api.loginOk("aqi")
        val list = phone.get("/api/v1/me/sessions").body<List<LoginSession>>()
        assertEquals(2, list.size)
        assertEquals(1, list.count { it.current })
        val tabletSession = tablet.get("/api/v1/me/sessions").body<List<LoginSession>>().single { it.current }

        other.delete("/api/v1/me/sessions/${tabletSession.id}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)
        phone.delete("/api/v1/me/sessions/${UuidV7.generate()}").assertProblem(HttpStatusCode.NotFound, ProblemCode.NotFound)

        assertEquals(HttpStatusCode.NoContent, phone.delete("/api/v1/me/sessions/${tabletSession.id}").status)
        tablet.get("/api/v1/me").assertProblem(HttpStatusCode.Unauthorized, ProblemCode.Unauthorized)
        assertTrue(phone.get("/api/v1/me").body<Me>().user.username == "aqi")
        assertEquals(1, phone.get("/api/v1/me/sessions").body<List<LoginSession>>().size)
    }
}
