package app.qichi.core.reminder

import app.qichi.shared.api.Event
import app.qichi.shared.api.Todo
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 哪些要提醒、排在几点（P16-01）。 */
class RemindersTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    private val room = UUID.randomUUID()

    /** 2026-09-24 周四 14:20（Asia/Shanghai） */
    private val now = Instant.parse("2026-09-24T06:20:00Z")
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun event(
        title: String,
        startsAt: Instant? = null,
        startDate: LocalDate? = null,
        remind: Int? = 15,
        participants: List<UUID> = emptyList(),
        deleted: Boolean = false,
    ) = Event(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0, updatedAt = t0,
        deletedAt = if (deleted) t0 else null, deletedBy = null, title = title, note = null, location = null,
        allDay = startDate != null, startsAt = startsAt, endsAt = startsAt?.plusSeconds(3600),
        startDate = startDate, endDate = startDate, participantIds = participants, createdBy = me, icsUid = null,
        remindMinutes = remind,
    )

    private fun todo(title: String, dueAt: Instant?, assignee: UUID? = null, done: Boolean = false, deleted: Boolean = false, dueDate: LocalDate? = null) = Todo(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0, updatedAt = t0,
        deletedAt = if (deleted) t0 else null, deletedBy = null, title = title, note = null, createdBy = me,
        assigneeId = assignee, parentId = null, dueDate = dueDate, dueAt = dueAt,
        recurrence = null, recurrencePrevId = null, doneAt = if (done) t0 else null, doneBy = null,
    )

    private fun remind(events: List<Event> = emptyList(), todos: List<Todo> = emptyList()) =
        upcomingReminders(room, events, todos, me, zone, now)

    @Test
    fun `定时日程在开始前 N 分钟响，准时的就在开始时响`() {
        val start = Instant.parse("2026-09-24T11:00:00Z") // 19:00
        val r = remind(listOf(event("晚饭", startsAt = start, remind = 15), event("电影", startsAt = start.plusSeconds(7200), remind = 0)))
        assertEquals(listOf("晚饭", "电影"), r.map { it.title })
        assertEquals(Instant.parse("2026-09-24T10:45:00Z"), r[0].at)
        assertEquals("15 分钟后开始", r[0].text)
        assertEquals(start.plusSeconds(7200), r[1].at)
        assertEquals("现在开始", r[1].text)
    }

    @Test
    fun `全天日程当天或前一天早上 9 点响，按房间时区`() {
        val tomorrow = LocalDate.of(2026, 9, 25)
        val r = remind(listOf(event("搬家", startDate = tomorrow, remind = 15), event("体检", startDate = tomorrow.plusDays(1), remind = 1440)))
        assertEquals(Instant.parse("2026-09-25T01:00:00Z"), r.single { it.title == "搬家" }.at) // 北京时间 9:00
        assertEquals(Instant.parse("2026-09-25T01:00:00Z"), r.single { it.title == "体检" }.at) // 前一天 9:00
        assertEquals("明天全天", r.single { it.title == "体检" }.text)
    }

    @Test
    fun `不提醒的、删掉的、只有对方参加的、已经过了的、太远的都不排`() {
        val soon = Instant.parse("2026-09-24T12:00:00Z")
        val r = remind(
            listOf(
                event("不提醒", startsAt = soon, remind = null),
                event("删掉的", startsAt = soon, deleted = true),
                event("只有对方", startsAt = soon, participants = listOf(partner)),
                event("有我", startsAt = soon, participants = listOf(partner, me)),
                event("已经过了", startsAt = now.plusSeconds(60), remind = 15),
                event("太远", startsAt = now.plus(java.time.Duration.ofDays(30))),
            ),
        )
        assertEquals(listOf("有我"), r.map { it.title })
    }

    @Test
    fun `待办：定了时刻、交给我或两个人、没做完的到点响；只有日期的不响`() {
        val at = Instant.parse("2026-09-24T09:30:00Z")
        val mine = todo("交房租", at, assignee = me)
        val r = remind(
            todos = listOf(
                mine,
                todo("两个人的", at.plusSeconds(60)),
                todo("交给对方", at, assignee = partner),
                todo("做完了", at, done = true),
                todo("删掉了", at, deleted = true),
                todo("只有日期", null, dueDate = LocalDate.of(2026, 9, 25)),
            ),
        )
        assertEquals(listOf("交房租", "两个人的"), r.map { it.title })
        assertEquals(at, r[0].at)
        assertEquals("todo:${mine.id}", r[0].key)
        assertEquals("qichi://room/$room/todo/${mine.id}", r[0].link)
    }

    @Test
    fun `按时间排，最多排 50 个；同一件事改了时间 key 不变`() {
        val many = (1..80).map { todo("t$it", now.plusSeconds(it * 600L)) }
        val r = remind(todos = many.shuffled())
        assertEquals(MAX_REMINDERS, r.size)
        assertTrue(r.zipWithNext().all { (a, b) -> !a.at.isAfter(b.at) })
        val e = event("晚饭", startsAt = Instant.parse("2026-09-24T11:00:00Z"))
        assertEquals(remind(listOf(e)).single().key, remind(listOf(e.copy(startsAt = e.startsAt!!.plusSeconds(600), remindMinutes = 60))).single().key)
    }

    @Test
    fun `纪念日：周年和整百天当天早上 9 点提醒，平常的日子不提醒`() {
        // 2024-09-27 是第 1 天：2026-09-27 是 2 周年，接下来 8 天里没有别的整百天
        val r = upcomingReminders(room, emptyList(), emptyList(), me, zone, now, anniversary = LocalDate.of(2024, 9, 27))
        assertEquals(1, r.size)
        assertEquals("今天是在一起 2 周年", r.single().text)
        assertEquals(Instant.parse("2026-09-27T01:00:00Z"), r.single().at)
        assertEquals("qichi://room/$room/today", r.single().link)
        // 2026-06-18 是第 1 天：第 100 天是 2026-09-25
        val hundred = upcomingReminders(room, emptyList(), emptyList(), me, zone, now, anniversary = LocalDate.of(2026, 6, 18))
        assertEquals(listOf("今天是在一起第 100 天"), hundred.map { it.text })
    }

    @Test
    fun `编辑面板里提醒的写法`() {
        assertEquals("不提醒", remindLabel(null, false))
        assertEquals("准时", remindLabel(0, false))
        assertEquals("15 分钟前", remindLabel(15, false))
        assertEquals("1 小时前", remindLabel(60, false))
        assertEquals("1 天前", remindLabel(1440, false))
        assertEquals("当天 9 点", remindLabel(15, true))
        assertEquals("前一天 9 点", remindLabel(1440, true))
    }

    @Test
    fun `到点时还算不算数：对方做完、删掉、改了时间的不弹；没变的照弹（P21-08）`() {
        val due = Instant.parse("2026-09-24T12:00:00Z")
        val open = todo("交水费", dueAt = due)
        val key = "todo:${open.id}"
        fun still(todos: List<Todo>) = reminderStillDue(key, due, room, emptyList(), todos, me, zone)
        assertTrue(still(listOf(open)))
        assertEquals(false, still(listOf(open.copy(doneAt = due.minusSeconds(600)))), "已经做完")
        assertEquals(false, still(listOf(open.copy(deletedAt = due.minusSeconds(600)))), "已经删掉")
        assertEquals(false, still(listOf(open.copy(dueAt = due.plusSeconds(3600)))), "改到了一小时后（新的闹钟另排）")
        assertEquals(false, still(emptyList()), "本机已经没有这条")

        val start = Instant.parse("2026-09-24T11:00:00Z")
        val dinner = event("晚饭", startsAt = start, remind = 15)
        val at = start.minusSeconds(15 * 60)
        assertTrue(reminderStillDue("event:${dinner.id}", at, room, listOf(dinner), emptyList(), me, zone))
        assertEquals(false, reminderStillDue("event:${dinner.id}", at, room, listOf(dinner.copy(remindMinutes = null)), emptyList(), me, zone), "关掉了提醒")
    }
}
