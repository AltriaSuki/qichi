package app.qichi.core.ui

import org.junit.Test
import kotlin.test.assertNotEquals

/** AI 回答的来源：服务端会引用的每一类都有自己的中文名（P11 起 AI 能查到更多类别）。 */
class SourceLabelsTest {
    private val cited = listOf(
        "message", "event", "todo", "plan", "idea", "archive_item", "decision", "mood",
        "qna_round", "document", "board_topic", "book", "review_document", "summary",
    )

    @Test
    fun `每一类来源都有中文名，不显示笼统的「记录」`() {
        cited.forEach { assertNotEquals("记录", sourceKind(it), it) }
    }
}
