package app.qichi.feature.writing

import app.qichi.shared.util.Authorship
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** 编辑框里的署名（P13-19）：后台算好的结果在正文又改了之后，按这次改动挪位置，和重新算的一致。 */
class LiveAuthorshipTest {
    private val partner = UUID.fromString("0192f000-aaaa-7bbb-8ccc-00000000000a")
    private val me = UUID.fromString("0192f000-aaaa-7bbb-8ccc-00000000000b")

    /** 对方写「我们周六去」「看日落」，我写「海边」 */
    private val base = listOf(Authorship.Run("我们周六去", partner), Authorship.Run("海边", me), Authorship.Run("看日落", partner))
    private val live = LiveAuthorship.compute(base, "我们周六去海边看日落", me)

    private fun recomputed(text: String) = LiveAuthorship.compute(base, text, me).partnerRanges

    @Test
    fun `算出对方写的位置和各人字数`() {
        assertEquals(listOf(0..4, 7..9), live.partnerRanges)
        assertEquals(mapOf(partner to 8, me to 2), live.counts)
        assertSame(live.partnerRanges, live.rangesFor("我们周六去海边看日落"))
    }

    @Test
    fun `还没重算时按改动挪位置，和重新算的一样`() {
        val edits = listOf(
            "我们早上周六去海边看日落", // 在对方的字前面写
            "我们周六去海边看日落。", // 写在最后
            "我们去海边看日落", // 删掉对方的两个字
            "我们周六去海边看晚日落", // 写进对方的一段中间
            "我们周六到山里看日落", // 选中一段跨两个人的字粘贴
            "", // 全删了
        )
        for (text in edits) assertEquals(recomputed(text), live.rangesFor(text), text)
    }

    @Test
    fun `连着写好几个字（一直没停手），位置一直对`() {
        // 每按一个键都拿同一份旧结果来挪
        val typed = "和你一起".runningFold("") { acc, c -> acc + c }.drop(1).map { "我们" + it + "周六去海边看日落" }
        for (t in typed) assertEquals(recomputed(t), live.rangesFor(t), t)
    }
}
