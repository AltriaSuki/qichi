package app.qichi.feature.todo

import app.qichi.core.database.SyncState
import app.qichi.core.sync.Local
import app.qichi.core.ui.TodoGroup
import app.qichi.shared.api.Todo
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** 编辑面板打开哪一条；桌面组件点进来时列表可能还没读出来（P15-02）。 */
class TodoEditTargetTest {
    private val t0 = Instant.parse("2026-09-24T02:00:00Z")
    private val todo = Todo(
        id = UUID.randomUUID(), roomId = UUID.randomUUID(), seq = 1, createdAt = t0, updatedAt = t0, deletedAt = null, deletedBy = null,
        title = "取回干洗的外套", note = null, createdBy = UUID.randomUUID(), assigneeId = null, parentId = null,
        dueDate = null, dueAt = null, recurrence = null, recurrencePrevId = null, doneAt = null, doneBy = null,
    )
    private val group = TodoGroup(Local(todo, SyncState.SYNCED), emptyList())
    private val find: (UUID) -> TodoGroup? = { if (it == todo.id) group else null }

    @Test
    fun `new 新建；找得到的打开它`() {
        assertEquals(TodoEditTarget.New, TodoEditTarget.of("new", loaded = false, find))
        assertEquals(TodoEditTarget.Existing(group), TodoEditTarget.of(todo.id.toString(), loaded = true, find))
        assertEquals(TodoEditTarget.Existing(group), TodoEditTarget.of(todo.id.toString(), loaded = false, find))
    }

    @Test
    fun `列表还没读出来时先等；读出来了还找不到就关掉；不是 id 的直接关掉`() {
        val other = UUID.randomUUID().toString()
        assertEquals(TodoEditTarget.Waiting, TodoEditTarget.of(other, loaded = false, find))
        assertEquals(TodoEditTarget.Gone, TodoEditTarget.of(other, loaded = true, find))
        assertEquals(TodoEditTarget.Gone, TodoEditTarget.of("abc", loaded = false, find))
    }
}
