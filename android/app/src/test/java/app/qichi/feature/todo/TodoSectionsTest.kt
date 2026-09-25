package app.qichi.feature.todo

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.shared.api.Todo
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class TodoSectionsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val room = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val now = Instant.parse("2026-09-24T02:00:00Z")
    // 2026-09-24 是星期四
    private val today = LocalDate.of(2026, 9, 24)

    private fun group(title: String, due: LocalDate?) = TodoGroup(
        Local(Todo(UUID.randomUUID(), room, 1, now, now, null, null, title, null, me, null, null, due, null, null, null, null, null), SyncState.SYNCED),
        emptyList(),
    )

    @Test
    fun `今天含逾期；这周到星期日；以后含没有截止日的`() {
        val s = todoSections(
            listOf(
                group("逾期", today.minusDays(3)), group("今天", today), group("周五", today.plusDays(1)),
                group("周日", today.plusDays(3)), group("下周一", today.plusDays(4)), group("没截止", null),
            ),
            today, zone,
        )
        assertEquals(listOf("逾期", "今天"), s.today.map { it.todo.value.title })
        assertEquals(listOf("周五", "周日"), s.week.map { it.todo.value.title })
        assertEquals(listOf("下周一", "没截止"), s.later.map { it.todo.value.title })
    }
}
