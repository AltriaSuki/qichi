package app.qichi.feature.widget

import app.qichi.shared.api.Todo
import app.qichi.shared.util.UuidV7
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 桌面待办组件列哪些、按什么顺序（P15-02）。 */
class WidgetTodosTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    private val room = UUID.randomUUID()

    /** 2026-09-24 周四 14:20（Asia/Shanghai） */
    private val now = Instant.parse("2026-09-24T06:20:00Z")
    private val today = LocalDate.of(2026, 9, 24)
    private val t0 = Instant.parse("2026-09-01T00:00:00Z")

    private fun todo(
        title: String,
        due: LocalDate? = today,
        dueAt: Instant? = null,
        assignee: UUID? = null,
        parent: UUID? = null,
        created: Long = 0,
        doneAt: Instant? = null,
        doneBy: UUID? = null,
        deleted: Boolean = false,
    ) = Todo(
        id = UUID.randomUUID(), roomId = room, seq = 1, createdAt = t0.plusSeconds(created), updatedAt = t0,
        deletedAt = if (deleted) t0 else null, deletedBy = null, title = title, note = null, createdBy = me,
        assigneeId = assignee, parentId = parent, dueDate = if (dueAt != null) null else due, dueAt = dueAt,
        recurrence = null, recurrencePrevId = null, doneAt = doneAt, doneBy = doneBy,
    )

    private fun titles(todos: List<Todo>) = widgetRows(todos, me, today, zone).map { it.todo.title }

    @Test
    fun `只列今天到期和过期的、交给我或两个人的顶层待办`() {
        val parent = todo("整理行李清单")
        val todos = listOf(
            parent,
            todo("交给我的", assignee = me),
            todo("两个人的"),
            todo("只交给对方的", assignee = partner),
            todo("明天的", due = today.plusDays(1)),
            todo("没有截止的", due = null),
            todo("子任务", parent = parent.id),
            todo("删掉的", deleted = true),
            todo("过期的", due = today.minusDays(2)),
            // 定了时刻的按房间时区算哪天：北京时间 25 号 01:00 是明天
            todo("明天凌晨的", dueAt = Instant.parse("2026-09-24T17:00:00Z")),
        )
        assertEquals(setOf("整理行李清单", "交给我的", "两个人的", "过期的"), titles(todos).toSet())
    }

    @Test
    fun `顺序：过期的在前（早的先），同一天定了时刻的按时刻在前，再按创建先后`() {
        val todos = listOf(
            todo("今天后建的", created = 9),
            todo("今天晚上六点", dueAt = Instant.parse("2026-09-24T10:00:00Z")),
            todo("昨天的", due = today.minusDays(1)),
            todo("今天先建的", created = 1),
            todo("今天早上九点", dueAt = Instant.parse("2026-09-24T01:00:00Z")),
            todo("上周的", due = today.minusDays(6)),
        )
        assertEquals(listOf("上周的", "昨天的", "今天早上九点", "今天晚上六点", "今天先建的", "今天后建的"), titles(todos))
    }

    @Test
    fun `无论谁勾选完成都立即从组件消失`() {
        val todos = listOf(
            todo("早上的", created = 1),
            todo("我刚勾掉的", created = 2, doneAt = now.minusSeconds(60), doneBy = me),
            todo("我一小时前勾掉的", created = 3, doneAt = now.minusSeconds(3600), doneBy = me),
            todo("对方刚勾掉的", created = 4, doneAt = now.minusSeconds(30), doneBy = partner),
            todo("晚上的", created = 5),
        )
        val rows = widgetRows(todos, me, today, zone)
        assertEquals(listOf("早上的", "晚上的"), rows.map { it.todo.title })
        assertEquals(2, rows.remaining())
        val justCompleted = todo("现在完成", doneAt = now, doneBy = me)
        assertTrue(widgetRows(listOf(justCompleted), me, today, zone).isEmpty())

    }

    @Test
    fun `右边的小字：过期的写哪天，今天定了时刻的写几点，只有日期的不写；晚了的用暮玫瑰色`() {
        val rows = widgetRows(
            listOf(
                todo("昨天的", due = today.minusDays(1)),
                todo("上周的", due = today.minusDays(6)),
                todo("早上九点", dueAt = Instant.parse("2026-09-24T01:00:00Z")),
                todo("晚上六点", dueAt = Instant.parse("2026-09-24T10:00:00Z")),
                todo("今天的"),
                todo("刚勾掉的", due = today.minusDays(1), doneAt = now, doneBy = me),
            ),
            me, today, zone,
        ).associateBy { it.todo.title }
        assertEquals("昨天", widgetDueLabel(rows.getValue("昨天的"), today, zone))
        assertEquals("09.18", widgetDueLabel(rows.getValue("上周的"), today, zone))
        assertEquals("09:00", widgetDueLabel(rows.getValue("早上九点"), today, zone))
        assertEquals("18:00", widgetDueLabel(rows.getValue("晚上六点"), today, zone))
        assertEquals(null, widgetDueLabel(rows.getValue("今天的"), today, zone))
        assertFalse("刚勾掉的" in rows)

        assertTrue(widgetIsLate(rows.getValue("昨天的"), now))
        assertTrue(widgetIsLate(rows.getValue("早上九点"), now), "今天九点已经过了")
        assertFalse(widgetIsLate(rows.getValue("晚上六点"), now))
        assertFalse(widgetIsLate(rows.getValue("今天的"), now))

    }

    /** 有待办时组件显示「无法显示内容」：列表编号不能是负数（Glance 留给自己用）。 */
    @Test
    fun `列表编号不是负数且各不相同`() {
        val ids = List(1_000) { UuidV7.generate() }
        val itemIds = ids.map(::widgetItemId)
        assertTrue(itemIds.all { it >= 0 })
        assertEquals(ids.size, itemIds.toSet().size)
    }
}
