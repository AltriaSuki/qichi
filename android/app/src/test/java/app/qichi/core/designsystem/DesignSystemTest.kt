package app.qichi.core.designsystem

import app.qichi.core.designsystem.component.markCharOf
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignSystemTest {

    private fun at(text: String) = skyAt(LocalTime.parse(text))

    @Test
    fun `天色边界时间`() {
        assertEquals(Sky.Night, at("00:00"))
        assertEquals(Sky.Night, at("04:59:59"))
        assertEquals(Sky.Dawn, at("05:00"))
        assertEquals(Sky.Dawn, at("07:59:59"))
        assertEquals(Sky.Day, at("08:00"))
        assertEquals(Sky.Day, at("16:59:59"))
        assertEquals(Sky.Dusk, at("17:00"))
        assertEquals(Sky.Dusk, at("19:29:59"))
        assertEquals(Sky.Night, at("19:30"))
        assertEquals(Sky.Night, at("23:59:59"))
    }

    @Test
    fun `每种天色都有自己的配色，只有深夜是深色`() {
        val all = Sky.entries.map(::colorsFor)
        assertEquals(4, all.map { it.background }.toSet().size)
        assertTrue(colorsFor(Sky.Night).isDark)
        assertFalse(colorsFor(Sky.Day).isDark)
    }

    @Test
    fun `大字模式放大 1点2 倍，日期大字不变`() {
        val large = DefaultTypography.scaled(QichiTypography.LARGE_TEXT_FACTOR)
        // 页面里写死的字号（28.tsp）按 scale 放大
        assertEquals(1f, DefaultTypography.scale)
        assertEquals(1.2f, large.scale)
        assertEquals(DefaultTypography.body.fontSize.value * 1.2f, large.body.fontSize.value, 0.001f)
        assertEquals(DefaultTypography.body.lineHeight.value * 1.2f, large.body.lineHeight.value, 0.001f)
        assertEquals(DefaultTypography.dateDisplay, large.dateDisplay)
        assertEquals(DefaultTypography, DefaultTypography.scaled(1f))
    }

    @Test
    fun `人物标记取显示名的最后一个字`() {
        assertEquals("栖", markCharOf("阿栖"))
        assertEquals("迟", markCharOf(" 小迟 "))
        assertEquals("n", markCharOf("Ann"))
        assertEquals("?", markCharOf(""))
    }
}
