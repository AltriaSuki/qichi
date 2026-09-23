package app.qichi.server.calendar

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcsCodecTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `全天结束日期按 ICS 排他规则换算并能回写`() {
        val source = """
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
        val (items, skipped) = IcsCodec.parse(source, zone)
        assertEquals(0, skipped)
        assertEquals(LocalDate.of(2026, 9, 22), items.single().endDate)
        assertEquals(items, IcsCodec.parse(IcsCodec.write(items), zone).first)
    }

    @Test fun `定时事件保留 UTC 时刻并跳过重复实例`() {
        val source = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Qichi Test//ZH
            BEGIN:VEVENT
            UID:dinner@example.test
            DTSTAMP:20260921T000000Z
            DTSTART:20260921T193000Z
            DTEND:20260921T203000Z
            SUMMARY:一起做饭
            END:VEVENT
            BEGIN:VEVENT
            UID:repeat@example.test
            DTSTAMP:20260921T000000Z
            DTSTART:20260921T193000Z
            DTEND:20260921T203000Z
            RRULE:FREQ=DAILY
            SUMMARY:重复事项
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n").toByteArray()
        val (items, skipped) = IcsCodec.parse(source, zone)
        assertEquals(1, skipped)
        assertEquals(Instant.parse("2026-09-21T19:30:00Z"), items.single().startsAt)
        assertTrue(String(IcsCodec.write(items)).contains("UID:dinner@example.test"))
    }
}
