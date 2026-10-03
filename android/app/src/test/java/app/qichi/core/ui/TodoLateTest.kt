package app.qichi.core.ui

import app.qichi.shared.api.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** 待办算不算过了截止（P21-13）：日期过了，或者今天的时刻已经过了；做完的不算。 */
class TodoLateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 3)
    /** 上海时间 10:00 */
    private val now = Instant.parse("2026-10-03T02:00:00Z")
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun todo(dueDate: LocalDate? = null, dueAt: Instant? = null, done: Boolean = false) = Todo(
        id = UUID.randomUUID(), roomId = UUID.randomUUID(), seq = 1, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
        title = "t", note = null, createdBy = UUID.randomUUID(), assigneeId = null, parentId = null, dueDate = dueDate, dueAt = dueAt,
        recurrence = null, recurrencePrevId = null, doneAt = if (done) t0 else null, doneBy = null,
    )

    @Test fun `今天定了时刻的：过了点才算过期；日期在今天之前的都算；做完的不算`() {
        assertEquals(true, todo(dueAt = Instant.parse("2026-10-03T01:53:00Z")).isLate(today, zone, now), "今天 9:53，现在 10:00")
        assertEquals(false, todo(dueAt = Instant.parse("2026-10-03T03:00:00Z")).isLate(today, zone, now), "今天 11:00 还没到")
        assertEquals(false, todo(dueDate = today).isLate(today, zone, now), "只定了今天，没定时刻")
        assertEquals(true, todo(dueDate = today.minusDays(1)).isLate(today, zone, now))
        assertEquals(false, todo(dueAt = Instant.parse("2026-10-03T01:53:00Z"), done = true).isLate(today, zone, now))
        assertEquals(false, todo().isLate(today, zone, now))
    }
}
