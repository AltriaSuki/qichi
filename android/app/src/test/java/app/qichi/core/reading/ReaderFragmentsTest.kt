package app.qichi.core.reading

import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderFragmentsTest {
    @Test fun `旧页面退出只清理自己创建的实例`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        val old = Fragment()
        val next = Fragment()
        val fm = activity.supportFragmentManager
        fm.beginTransaction().add(container.id, old, "${ReaderFragments.TAG}.old").commitNow()
        fm.beginTransaction().add(container.id, next, "${ReaderFragments.TAG}.next").commitNow()
        ReaderFragments.removeOwned(fm, old)
        assertFalse(old.isAdded)
        assertTrue(next.isAdded)
        ReaderFragments.dropRestored(fm)
        assertFalse(next.isAdded)
        controller.pause().stop().destroy()
    }
}
