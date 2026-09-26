package app.qichi.core.sync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「这个房间有新版 App 才懂的内容」的记录（P13-07）。 */
class UnknownContentTest {
    private val room = UUID.fromString("0192f000-0000-7000-8000-00000000000a")
    private val other = UUID.fromString("0192f000-0000-7000-8000-00000000000b")

    @Test
    fun `当前版本记下的：提示更新，不重新快照`() {
        val unknown = UnknownContent(UnknownContent.MemoryStore(), currentVersion = 10)
        assertFalse(unknown.needsNewerApp.value)
        unknown.mark(room)
        assertTrue(unknown.needsNewerApp.value)
        assertFalse(unknown.needsRebootstrap(room))
        assertFalse(unknown.needsRebootstrap(other))
    }

    @Test
    fun `升级后：旧版本的记录要重新快照；快照全认得就清掉，还有认不出来的就记成新版本`() {
        val store = UnknownContent.MemoryStore()
        UnknownContent(store, currentVersion = 10).apply {
            mark(room)
            mark(other)
        }
        val v11 = UnknownContent(store, currentVersion = 11)
        assertFalse(v11.needsNewerApp.value)
        assertTrue(v11.needsRebootstrap(room))

        // 重新快照之前又翻到认不出来的：留着旧记录，快照照样要做
        v11.mark(room)
        assertTrue(v11.needsRebootstrap(room))

        v11.rebootstrapped(room, incomplete = false)
        assertFalse(v11.needsRebootstrap(room))
        assertFalse(v11.needsNewerApp.value)

        v11.rebootstrapped(other, incomplete = true)
        assertFalse(v11.needsRebootstrap(other))
        assertTrue(v11.needsNewerApp.value)
    }

    @Test
    fun `登出时清掉`() {
        val unknown = UnknownContent(UnknownContent.MemoryStore(), currentVersion = 10)
        unknown.mark(room)
        unknown.clearAll()
        assertFalse(unknown.needsNewerApp.value)
        assertFalse(unknown.needsRebootstrap(room))
    }
}
