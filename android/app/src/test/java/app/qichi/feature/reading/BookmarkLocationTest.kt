package app.qichi.feature.reading

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookmarkLocationTest {
    private fun locator(locations: String, href: String = "ch1.xhtml") =
        Locator.fromJSON(JSONObject("""{"href":"$href","type":"application/xhtml+xml","locations":$locations}"""))!!

    @Test fun `长书相邻页不能删除彼此的书签`() {
        assertFalse(sameBookmarkLocation(locator("""{"progression":0.1,"totalProgression":0.5}"""), locator("""{"progression":0.2,"totalProgression":0.501}""")))
    }

    @Test fun `缺少总进度时仍能切换当前书签`() {
        assertTrue(sameBookmarkLocation(locator("""{"progression":0.3}"""), locator("""{"progression":0.3}""")))
        assertTrue(sameBookmarkLocation(locator("""{"position":12}"""), locator("""{"position":12}""")))
        assertFalse(sameBookmarkLocation(locator("""{"position":12}"""), locator("""{"position":13}""")))
        assertFalse(sameBookmarkLocation(locator("{}"), locator("{}", "ch2.xhtml")))
    }
}
