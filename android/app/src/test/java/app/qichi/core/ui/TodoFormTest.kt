package app.qichi.core.ui

import app.qichi.core.data.People
import app.qichi.shared.api.Patch
import app.qichi.shared.api.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Test

/** 待办的编辑表单：只发改动过的字段。 */
class TodoFormTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val people = People(null, emptyList(), UUID.randomUUID())
    private val t0 = Instant.parse("2026-09-27T01:00:00Z")

    private fun todo(dueDate: LocalDate? = null, dueAt: Instant? = null) = Todo(
        id = UUID.randomUUID(), roomId = UUID.randomUUID(), seq = 1, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
        title = "去取快递", note = null, createdBy = UUID.randomUUID(), assigneeId = null, parentId = null,
        dueDate = dueDate, dueAt = dueAt, recurrence = null, recurrencePrevId = null, doneAt = null, doneBy = null,
    )

    @Test
    fun `有具体时刻的待办（AI 记下的）只改名字：不动截止，时刻留着`() {
        // 以前会同时发日期、留着时刻，服务端「只能设日期或时刻其中一个」拒收，改动一直发不出去
        val t = todo(dueAt = Instant.parse("2026-09-28T07:00:00Z"))
        val change = TodoForm.of(t, people, zone).copy(title = "去取两个快递").changesFrom(t, people, zone)
        assertEquals(Patch.of("去取两个快递"), change.title)
        assertEquals(Patch.Absent, change.dueDate)
        assertEquals(Patch.Absent, change.dueAt)
    }

    @Test
    fun `有具体时刻的待办改了日期：时刻留着，挪到新的那天`() {
        val t = todo(dueAt = Instant.parse("2026-09-28T07:00:00Z"))
        val change = TodoForm.of(t, people, zone).copy(dueDate = LocalDate.of(2026, 9, 30)).changesFrom(t, people, zone)
        assertEquals(Patch.Absent, change.dueDate)
        assertEquals(Patch.of(Instant.parse("2026-09-30T07:00:00Z")), change.dueAt)
    }

    @Test
    fun `去掉时刻：换成只有日期`() {
        val t = todo(dueAt = Instant.parse("2026-09-28T07:00:00Z"))
        val change = TodoForm.of(t, people, zone).copy(dueTime = null).changesFrom(t, people, zone)
        assertEquals(Patch.of(LocalDate.of(2026, 9, 28)), change.dueDate)
        assertEquals(Patch.of(null), change.dueAt)
    }

    @Test
    fun `只有日期的待办加上时刻：发 dueAt（按房间时区），日期清掉`() {
        val t = todo(dueDate = LocalDate.of(2026, 9, 28))
        val form = TodoForm.of(t, people, zone)
        assertEquals(null, form.dueTime)
        val change = form.copy(dueTime = LocalTime.of(20, 30)).changesFrom(t, people, zone)
        assertEquals(Patch.of(null), change.dueDate)
        assertEquals(Patch.of(Instant.parse("2026-09-28T12:30:00Z")), change.dueAt)
    }

    @Test
    fun `时刻按分钟比：带秒数的时刻没改就不发`() {
        val t = todo(dueAt = Instant.parse("2026-09-28T07:00:42Z"))
        val form = TodoForm.of(t, people, zone)
        assertEquals(LocalTime.of(15, 0), form.dueTime)
        val change = form.copy(note = "带上身份证").changesFrom(t, people, zone)
        assertEquals(Patch.Absent, change.dueDate)
        assertEquals(Patch.Absent, change.dueAt)
    }

    @Test
    fun `新建时截止二选一；没有日期时时刻不算`() {
        val today = LocalDate.of(2026, 9, 27)
        assertEquals(LocalDate.of(2026, 9, 28) to null, TodoForm(title = "a", dueDate = LocalDate.of(2026, 9, 28)).fixed(today).due(zone))
        assertEquals(null to Instant.parse("2026-09-28T01:00:00Z"), TodoForm(title = "a", dueDate = LocalDate.of(2026, 9, 28), dueTime = LocalTime.of(9, 0)).fixed(today).due(zone))
        assertEquals(null to null, TodoForm(title = "a", dueTime = LocalTime.of(9, 0)).fixed(today).due(zone))
    }

    @Test
    fun `只有日期的待办：改日期发日期，不改不发；选了重复没选日期时取今天`() {
        val t = todo(dueDate = LocalDate.of(2026, 9, 28))
        assertEquals(Patch.Absent, TodoForm.of(t, people, zone).copy(note = "在驿站").changesFrom(t, people, zone).dueDate)
        assertEquals(Patch.of(LocalDate.of(2026, 9, 29)), TodoForm.of(t, people, zone).copy(dueDate = LocalDate.of(2026, 9, 29)).changesFrom(t, people, zone).dueDate)
        val today = LocalDate.of(2026, 9, 27)
        assertEquals(today, TodoForm(title = "背单词", repeat = Repeat.Daily).fixed(today).dueDate)
        assertEquals(null, TodoForm(title = "背单词").fixed(today).dueDate)
    }
}
