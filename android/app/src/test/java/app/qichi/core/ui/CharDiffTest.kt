package app.qichi.core.ui

import app.qichi.core.ui.CharDiff.Kind
import app.qichi.core.ui.CharDiff.Piece
import org.junit.Test
import kotlin.test.assertEquals

class CharDiffTest {
    @Test
    fun `改错别字：只标出换掉的那几个字`() {
        assertEquals(
            listOf(Piece(Kind.Same, "在海边"), Piece(Kind.Removed, "座"), Piece(Kind.Added, "坐"), Piece(Kind.Same, "了很久，心里很平"), Piece(Kind.Removed, "净"), Piece(Kind.Added, "静"), Piece(Kind.Same, "。")),
            CharDiff.diff("在海边座了很久，心里很平净。", "在海边坐了很久，心里很平静。"),
        )
    }

    @Test
    fun `一样的、全换的、太长的`() {
        assertEquals(listOf(Piece(Kind.Same, "不变")), CharDiff.diff("不变", "不变"))
        assertEquals(listOf(Piece(Kind.Removed, "甲"), Piece(Kind.Added, "乙")), CharDiff.diff("甲", "乙"))
        val long = "字".repeat(1_500)
        assertEquals(listOf(Piece(Kind.Removed, long), Piece(Kind.Added, long + "。")), CharDiff.diff(long, long + "。"))
    }
}
