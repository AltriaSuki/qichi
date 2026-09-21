package app.qichi.feature.chat

import androidx.compose.ui.unit.dp
import app.qichi.core.data.AttachmentPreparer
import app.qichi.core.ui.formatBytes
import org.junit.Test
import kotlin.test.assertEquals

class AttachmentRulesTest {

    @Test
    fun `图片气泡按比例，宽不超过 220、高不超过 260，细长图保留最短边`() {
        assertEquals(220.dp to 165.dp, imageBubbleSize(4000, 3000))
        assertEquals(195.dp to 260.dp, imageBubbleSize(3000, 4000))
        assertEquals(220.dp to 96.dp, imageBubbleSize(3000, 300), "很宽的图：高度不小于 96（显示时裁切）")
        assertEquals(96.dp to 260.dp, imageBubbleSize(100, 3000))
        assertEquals(180.dp to 132.dp, imageBubbleSize(null, null))
    }

    @Test
    fun `上传前缩图：长边不超过 2560，不放大`() {
        assertEquals(2560 to 1920, AttachmentPreparer.targetSize(4000, 3000))
        assertEquals(1440 to 2560, AttachmentPreparer.targetSize(2250, 4000))
        assertEquals(800 to 600, AttachmentPreparer.targetSize(800, 600))
        assertEquals("IMG_2031.jpg", AttachmentPreparer.jpegName("IMG_2031.HEIC"))
        assertEquals("image.jpg", AttachmentPreparer.jpegName(null))
    }

    @Test
    fun `文件大小的说法`() {
        assertEquals("45 B", formatBytes(45))
        assertEquals("218 KB", formatBytes(218 * 1024L))
        assertEquals("1 KB", formatBytes(1100))
        assertEquals("3.4 MB", formatBytes((3.4 * 1024 * 1024).toLong()))
    }
}

class SearchHighlightTest {
    @Test
    fun `搜索词的每一处都标出来，不分大小写`() {
        val text = highlight("Offline 和 offline", "offline", androidx.compose.ui.graphics.Color.Red)
        assertEquals(listOf(0 to 7, 10 to 17), text.spanStyles.map { it.start to it.end })
        assertEquals(0, highlight("河边", " ", androidx.compose.ui.graphics.Color.Red).spanStyles.size)
    }
}
