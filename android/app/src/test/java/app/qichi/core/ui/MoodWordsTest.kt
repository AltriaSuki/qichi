package app.qichi.core.ui

import app.qichi.shared.model.MoodLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoodWordsTest {
    @Test
    fun `设计稿的例子：疲惫 6 是「有点累」`() {
        assertEquals("有点累", feelingWord(MoodLabel.Tired, 6))
    }

    @Test
    fun `强度分三档`() {
        assertEquals("有些倦", feelingWord(MoodLabel.Tired, 3))
        assertEquals("有点累", feelingWord(MoodLabel.Tired, 4))
        assertEquals("很累", feelingWord(MoodLabel.Tired, 7))
    }

    @Test
    fun `每个标签每档都有词`() {
        MoodLabel.entries.forEach { label -> (1..10).forEach { assertTrue(feelingWord(label, it).isNotBlank()) } }
    }
}
