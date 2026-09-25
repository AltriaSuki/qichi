package app.qichi.shared.util

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorshipTest {
    private val qi = UUID.randomUUID()   // 阿栖
    private val chi = UUID.randomUUID()  // 小迟

    private fun textOf(runs: List<Authorship.Run>, who: UUID) = runs.filter { it.authorId == who }.joinToString("|") { it.text }

    @Test
    fun `验收的例子：阿栖写 v1、小迟加一句、阿栖改一个字，只有小迟那句算小迟的`() {
        val v1 = "窗边的绿萝又长了一截。"
        val v2 = v1 + "你说，等它爬到书架顶，我们就去看一次海。"
        val v3 = v2.replaceFirst("一截", "一段")
        val runs = Authorship.attribute(listOf(v1 to qi, v2 to chi, v3 to qi))
        assertEquals(v3, runs.joinToString("") { it.text })
        assertEquals("你说，等它爬到书架顶，我们就去看一次海。", textOf(runs, chi))
        val counts = Authorship.counts(runs)
        assertEquals(CjkText.charCount("你说，等它爬到书架顶，我们就去看一次海。"), counts[chi])
        assertEquals(CjkText.charCount(v3) - counts[chi]!!, counts[qi])
    }

    @Test
    fun `两人交替改同一段：谁最后写的字算谁的`() {
        val v1 = "周六去海边。"
        val v2 = "周六早上去海边。"        // 小迟加了「早上」
        val v3 = "周六早上八点去海边。"    // 阿栖加了「八点」
        val runs = Authorship.attribute(listOf(v1 to qi, v2 to chi, v3 to qi))
        assertEquals("早上", textOf(runs, chi))
        assertEquals("周六|八点去海边。", textOf(runs, qi))
    }

    @Test
    fun `删了又加：重新加回来的字算加的人的`() {
        val v1 = "带上相机和外套。"
        val v2 = "带上外套。"              // 小迟删了「相机和」
        val v3 = "带上相机和外套。"        // 阿栖又加回来
        val runs = Authorship.attribute(listOf(v1 to chi, v2 to chi, v3 to qi))
        assertEquals("相机和", textOf(runs, qi))
        assertEquals("带上|外套。", textOf(runs, chi))
    }

    @Test
    fun `只改标点：那个标点算改的人的，字数不变`() {
        val v1 = "我们去看海吧，好不好"
        val v2 = "我们去看海吧，好不好？"  // 小迟加了问号
        val runs = Authorship.attribute(listOf(v1 to qi, v2 to chi))
        assertEquals("？", textOf(runs, chi))
        assertEquals(null, Authorship.counts(runs)[chi]?.takeIf { it > 0 }, "标点不算字")
    }

    @Test
    fun `表情和英文也按字符对齐；没有版本时为空`() {
        val runs = Authorship.attribute(listOf("hello 🌊" to qi, "hello 🌊 world" to chi))
        assertEquals(" world", textOf(runs, chi))
        assertEquals(emptyList(), Authorship.attribute(emptyList()))
    }

    @Test
    fun `接着算一版和从头算结果一样`() {
        val versions = listOf("周六去海边。" to qi, "周六早上去海边。" to chi, "周六早上八点去海边！" to qi)
        val stepwise = Authorship.extend(Authorship.attribute(versions.take(2)), versions[2].first, qi)
        assertEquals(Authorship.attribute(versions), stepwise)
    }
}
