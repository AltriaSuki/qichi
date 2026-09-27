package app.qichi.core.ui

import org.junit.Test
import kotlin.test.assertEquals

/** AI 的回答存成灵感、文稿、档案时的正文和标题（P14-04）。 */
class AiTextTest {
    @Test
    fun `正文去掉来源编号，别的不动`() {
        assertEquals("民宿订好了，在东山岛。车程两小时。", plainAiAnswer(" 民宿订好了，在东山岛[1]。车程两小时 [2][3]。 "))
        assertEquals("第 [一] 步", plainAiAnswer("第 [一] 步"), "不是编号的方括号留着")
    }

    @Test
    fun `标题取问题的第一行，长了截断，没有问题时用默认的`() {
        assertEquals("周六去哪？", aiAnswerTitle("  周六去哪？\n顺便想想吃什么", 20))
        assertEquals("这段和我们最近的生活有什么…", aiAnswerTitle("这段和我们最近的生活有什么呼应？", 14))
        assertEquals("AI 的回答", aiAnswerTitle(null, 20))
        assertEquals("AI 的回答", aiAnswerTitle("   ", 20))
    }
}
