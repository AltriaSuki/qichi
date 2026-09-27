package app.qichi.core.sync

import app.qichi.shared.api.Idea
import app.qichi.shared.model.EntityType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** 解过的行记住（B1）：Room 让每个页面重读整张表时，没变的行不再重新解析 JSON。 */
class DecodeCacheTest {
    private val room = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000001")
    private val author = UUID.fromString("0192f000-aaaa-7bbb-8ccc-000000000002")

    private fun idea(body: String) = Idea(
        UUID.randomUUID(), room, 1, Instant.EPOCH, Instant.EPOCH, null, null, author, body,
    )

    @Test
    fun `同样的内容只解一次，拿到同一个对象；内容变了重新解`() {
        val first = idea("阳台种柠檬树")
        // 每次都是新的字符串（像从数据库里重新读出来的一样）
        val a = LocalStore.decode(EntityType.Idea, String(LocalStore.encode(EntityType.Idea, first).toCharArray()))
        val b = LocalStore.decode(EntityType.Idea, String(LocalStore.encode(EntityType.Idea, first).toCharArray()))
        assertSame(a, b)
        assertEquals(first, a)

        val changed = first.copy(body = "阳台种柠檬树和薄荷")
        val c = LocalStore.decode(EntityType.Idea, LocalStore.encode(EntityType.Idea, changed))
        assertNotSame(a, c)
        assertEquals(changed, c)
    }

    @Test
    fun `记住的条数有上限，最久没用的先丢`() {
        val oldest = LocalStore.encode(EntityType.Idea, idea("最早的一条"))
        val kept = LocalStore.decode(EntityType.Idea, oldest)
        repeat(LocalStore.DECODE_CACHE_MAX) { LocalStore.decode(EntityType.Idea, LocalStore.encode(EntityType.Idea, idea("第 $it 条"))) }
        val again = LocalStore.decode(EntityType.Idea, oldest)
        assertEquals(kept, again)
        assertNotSame(kept, again, "超出上限后最早的那条已经丢了，重新解析")
    }
}
