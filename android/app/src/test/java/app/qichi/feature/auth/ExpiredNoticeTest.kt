package app.qichi.feature.auth

import app.qichi.core.auth.ExpiredSession
import app.qichi.core.auth.SessionEnd
import app.qichi.core.auth.SessionEndReason
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** 登录页上「登录已失效」那句话写清楚什么时候、为什么（P21-07）。 */
class ExpiredNoticeTest {
    private val user = UUID.randomUUID()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = Instant.parse("2026-10-02T15:14:00Z")

    @Test fun `服务端不认了、读不出来、不知道原因，各说各的；没发出去的条数接在后面`() {
        assertEquals(
            "登录已失效（10月2日 23:14，服务器不再认这台手机的登录），请重新登录。还有 2 条内容没发出去，登录同一个账号后会接着发。",
            expiredNotice(ExpiredSession(user, 2, SessionEnd(SessionEndReason.Rejected, at)), zone),
        )
        assertEquals(
            "这台手机上保存的登录信息读不出来了（10月2日 23:14），请重新登录。",
            expiredNotice(ExpiredSession(user, 0, SessionEnd(SessionEndReason.Unreadable, at)), zone),
        )
        assertEquals("登录已失效，请重新登录。", expiredNotice(ExpiredSession(user, 0), zone))
    }
}
