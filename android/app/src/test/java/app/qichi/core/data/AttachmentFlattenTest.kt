package app.qichi.core.data

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 带透明的图转 JPEG 前铺白底（P21-10）：以前透明处变成黑色。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class AttachmentFlattenTest {
    @Test fun `透明处变白，不透明的颜色不变`() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.TRANSPARENT)
            setPixel(1, 0, Color.RED)
        }
        val flat = AttachmentPreparer.flattenOnWhite(source)
        assertFalse(flat.hasAlpha())
        assertEquals(Color.WHITE, flat.getPixel(0, 0))
        assertEquals(Color.RED, flat.getPixel(1, 0))
    }
}
