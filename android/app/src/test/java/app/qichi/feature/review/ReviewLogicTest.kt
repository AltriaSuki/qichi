package app.qichi.feature.review

import androidx.compose.ui.geometry.Offset
import app.qichi.core.sync.Local
import app.qichi.core.database.SyncState
import app.qichi.shared.api.Annotation
import app.qichi.shared.api.AnnotationAnchor
import app.qichi.shared.api.AnnotationReply
import app.qichi.shared.api.NormRect
import app.qichi.shared.api.ReviewPage
import app.qichi.shared.api.ReviewVersion
import app.qichi.shared.api.TextBlock
import app.qichi.shared.model.AnchorKind
import app.qichi.shared.model.AnnotationKind
import app.qichi.shared.model.AnnotationStatus
import app.qichi.shared.model.PreviewStatus
import app.qichi.shared.model.ReviewFormat
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewLogicTest {
    private val page = ReviewPage(
        2, 595.0, 842.0, UUID.randomUUID(),
        listOf(
            TextBlock("p2-b1", AnchorKind.Paragraph, NormRect(0.1, 0.1, 0.8, 0.2), "整段说明"),
            TextBlock("p2-b2", AnchorKind.Paragraph, NormRect(0.2, 0.15, 0.2, 0.03), "里面的小段"),
        ),
        listOf(NormRect(0.5, 0.6, 0.3, 0.2)),
    )

    @Test fun `点一下：选中最小的那块文字；点图片选图片；空白处在幻灯片里选整页，其它取消`() {
        assertEquals("p2-b2", hitTest(page, ReviewFormat.Text, 0.25f, 0.16f)?.ref)
        assertEquals("p2-b1", hitTest(page, ReviewFormat.Text, 0.8f, 0.25f)?.ref)
        assertEquals(AnchorKind.Image, hitTest(page, ReviewFormat.Text, 0.6f, 0.7f)?.kind)
        assertNull(hitTest(page, ReviewFormat.Text, 0.05f, 0.95f))
        assertEquals(AnchorKind.Slide, hitTest(page, ReviewFormat.Slides, 0.05f, 0.95f)?.kind)
    }

    @Test fun `长按拖出区域：带上区域里的文字；太小的不算`() {
        val sel = regionSelection(page, Offset(100f, 100f), Offset(300f, 180f), 1000f, 1000f)!!
        assertEquals(AnchorKind.Region, sel.kind)
        assertEquals(0.1, sel.rect!!.x, 1e-6)
        assertEquals("整段说明 里面的小段", sel.quote)
        assertNull(regionSelection(page, Offset(100f, 100f), Offset(105f, 102f), 1000f, 1000f))
    }

    @Test fun `批注列表：按页、按上下位置编号；带过来的批注连同前一版的讨论一起算，标出来自哪一版`() {
        val t = Instant.parse("2026-09-21T10:00:00Z")
        val room = UUID.randomUUID()
        val doc = UUID.randomUUID()
        fun v(n: Int) = ReviewVersion(UUID.randomUUID(), room, n.toLong(), t, t, null, null, doc, n, UUID.randomUUID(), "a.pdf", ReviewFormat.Pdf, room, PreviewStatus.Ready, 3, null)
        val v1 = v(1)
        val v2 = v(2)
        fun ann(version: ReviewVersion, page: Int, y: Double, carried: UUID? = null) = Annotation(
            UUID.randomUUID(), room, 1, t, t, null, null, doc, version.id, AnnotationAnchor(page, AnchorKind.Region, NormRect(0.1, y, 0.2, 0.1)),
            room, AnnotationKind.Comment, AnnotationStatus.Open, "x", carried, false, null, null,
        )
        val old = ann(v1, 1, 0.5)
        val carried = ann(v2, 2, 0.5, carried = old.id)
        val top = ann(v2, 2, 0.1)
        val first = ann(v2, 1, 0.9)
        fun reply(a: Annotation, at: Long) = Local(AnnotationReply(UUID.randomUUID(), room, 1, t.plusSeconds(at), t, null, null, a.id, room, "r$at"), SyncState.SYNCED)
        val items = annotationItems(
            listOf(old, carried, top, first).map { Local(it, SyncState.SYNCED) },
            listOf(reply(old, 1), reply(carried, 2)),
            listOf(v1, v2), v2.id,
        )
        assertEquals(listOf(first.id, top.id, carried.id), items.map { it.value.id })
        assertEquals(listOf(1, 2, 3), items.map { it.number })
        assertEquals(listOf("r1", "r2"), items[2].replies.map { it.body })
        assertEquals(1, items[2].carriedFrom)
        assertNull(items[0].carriedFrom)
    }
}
